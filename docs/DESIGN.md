# Waste Collection Dispatch Actor Design — WasteDispatch-LLM as a contained intelligence node

非危険物廃棄物の収集配車・報告サービスを、governed schedule-collect-report
の運用で、SaaS課金に依存せず OSS の actor として自前運用するための設計。
`cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で封じ込め
た構図)を、廃棄物収集ドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか、そしてなぜスコープを絞るのか

pickup intake の正規化・マニフェスト記録・開示列の提案は LLM で加速できる。
しかし LLM は次の理由で**スケジュール確定・記録・開示の最終権限を持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 危険物フラグ付きストリームを非危険物として通す | 規制違反・環境リスク |
| 施設の許可容量を超えてスケジュールする | 環境許可違反 |
| 出典なしに分類を「提案」で確定 | 誤分類データの伝播 |
| 契約 tier を超えた列を開示 | 過剰開示・契約違反 |

したがって設計課題は「LLM で廃棄物収集を回す」ことではなく、**「LLM を
信頼境界の内側に封じ込め、危険物判定・施設許可容量・出典・人間レビューの
層をどう被せるか」**である。オーナーの確認により、対象は**非危険物廃棄物
の収集・保管・配送のみ**(危険物の取扱いは一切含まない)に絞られる。

## 2. アクター・トポロジ(監督ツリー)

```
WasteDispatchSystem (root supervisor)
│
├── ScheduleActor ……… pickup intakeの正規化・スケジュール(:pickup/schedule)
├── ManifestActor ……… 収集後の実重量記録(:manifest/record)
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; WasteDispatch-LLM 封じ込め ★
│     ├── WasteDispatch-LLM (sealed)  proposal only(src/wastecollect/llm.cljc)
│     ├── WasteDispatchGovernor       INDEPENDENT ゲート(src/wastecollect/policy.cljc)
│     ├── Committer                    SSoT/台帳への書き込み(src/wastecollect/store.cljc)
│     └── Recorder                     監査台帳(append-only)
│
├── ReviewActor ……… 人間レビュー(bulk pickup・紛争申立ての interrupt を受ける)
└── DisclosureActor ……… governed read(report.cljc、契約 tier 列のみ)
```

原則:

1. **WasteDispatch-LLM は最下層ノードで、台帳・開示経路に直接触れない。**
   出力は常に WasteDispatchGovernor で検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold(スケジュール/
   記録/開示しない)** に倒す。
3. **すべてが台帳に積まれる。** 「誰が・何を・どの契約/出典でスケジュール/
   開示したか」は監査台帳への Datalog クエリ — 監査・紛争追跡が同一ファクト
   ログから出る。

## 3. OperationActor 内部(WasteDispatch-LLM ラッパー)

`src/wastecollect/operation.cljc` の langgraph-clj StateGraph として実装。
**1 run = 1 操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

### 3.1 注入される3つの依存(すべて swap)

- **Store**(`wastecollect.store/Store` プロトコル): `MemStore`(既定)/
  `DatomicStore`(`langchain.db` = Datomic-API 互換 EAV)。両者は同一契約
  テストで等価性を保証。
- **Advisor**(`wastecollect.llm/Advisor` プロトコル): `mock-advisor`
  (既定)/ `llm-advisor`(`langchain.model` の ChatModel)。応答破損時は
  confidence 0 の noop に落ち、LLM 不調が auto-commit/公開にならない。
- **Phase**(`wastecollect.phase`、context の `:phase 0..3`): 段階導入。
  **`:dispute/request` はどの phase の `:auto` にも入らない**(恒久ゲート)。
  `default-phase` は保守的な `1` — 実装当初から fail-open バグを回避。

## 4. WasteDispatchGovernor(独立検閲層)

`src/wastecollect/policy.cljc`。LLM とは別経路で、提案を可決/拒否/escalate
に判定する。判定の優先順位(上が強い、HARD は人間承認でも上書き不可):

1. **RBAC**
2. **hazard-misclassification-gate**(このactor固有) — 危険物フラグが1つ
   でも立っていたら無条件拒否。非危険物専用という構造的境界そのもの。
3. **facility-permit-capacity-gate**(このactor固有) — 施設の当該
   waste-class の日次許可容量を超過、または許可自体が無ければ拒否。
4. **source-provenance-gate** — 分類根拠の出典が
   `wastecollect.facts/allowed-source-classes` に無ければ拒否。
5. **licensed-disclosure** — 契約 tier 超過の開示は拒否。
6. **確信度フロア** — `:confidence < 0.6` → escalate(soft)。
7. **bulk-volume gate** — 大口(1000kg以上)pickup は必ず人間承認(soft)。
8. **dispute-request** — 紛争は常に escalate(soft だが無条件)。

## 5. SSoT と監査台帳

`src/wastecollect/store.cljc`。dev は in-mem の EDN 事実層(本番は Datomic)。

- **entities**: `generators` `facilities`(waste-class別の日次許可容量)
  `pickups` `manifests` `contracts`(client billing)。
- **commit-record!**: `:manifest-record` は manifest 記録と同時に施設の
  `facility-intake` 累計を更新する(次のスケジュール判定で capacity-gate
  が参照)。
- **append-ledger!**: 全 commit/reject/開示を**不変台帳**に積む。

## 6. デモ(`clojure -M:dev:run`)

`src/wastecollect/sim.cljc` が8操作を actor に通す(§sim.cljc docstring
参照): 正当なスケジュール → commit、出典なし → hold、tier超過/未契約の
開示 → hold ×2、危険物フラグ → hold、紛争申立て → 人間承認 → commit、
施設容量超過 → hold、大口pickup → 人間承認 → commit。

## 7. テスト(`clojure -M:dev:test`)

`test/wastecollect/policy_contract_test.clj` が**ガバナンス契約を実行可能**
にする。`test/wastecollect/phase_test.clj` が段階導入と「紛争は恒久的に
人間専用」を保証。`test/wastecollect/facts_test.clj` が分類根拠カタログ
自体の正直さ(捏造禁止)を保証。
