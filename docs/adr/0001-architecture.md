# ADR-0001: cloud-itonami-isic-3811 — WasteDispatch-LLM を封じ込めた知能ノードとする非危険物廃棄物収集アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図の直接の手本)、`cloud-itonami-isic-7820`(同一パターンの
  もう一つの実例)、robotaxi-actor ADR-0001(研究モデルを信頼境界に封じ込
  める actor 設計)、langgraph ADR-0001(Pregel superstep + interrupt +
  Datomic checkpoint)
- 文脈: com-junkawasaki/root superproject ADR-2607113200(本 ADR の対、
  経緯・スコープ決定の全文はそちら)

## 課題

`kotoba-lang/industry` registry の未着手 `:spec` スロットから対象を選定
した。ISIC Rev.4 3811「Collection of non-hazardous waste」は、収集・配送
の物理的作動を伴うが、その中核リスクは配送そのものではなく**「非危険物
専用の収集業が、危険物を誤って非危険物として受け入れてしまう」**という
分類の誤りである。

pickup intake の正規化・マニフェスト記録には LLM が有効だが、**LLM に
スケジュール確定・記録・開示を直接行わせるのは危険**である(危険物の
誤分類=規制違反・環境リスク、施設許可容量の超過=環境許可違反、出典なき
分類の断定=誤情報伝播)。

## 決定

### 1. WasteDispatch-LLM は最下層の1ノードに封じ込め、直接スケジュール/記録/開示させない

**単一の不変条件**:

> **WasteDispatch-LLM は、WasteDispatchGovernor が拒否するpickupの
> スケジュール確定・マニフェスト記録・開示・紛争解決を決して行わない。**

### 2. WasteDispatchGovernor は8チェック(HARD5 + SOFT3)

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | **hazard-misclassification-gate**(新規、この業態固有) | HARD | 危険物フラグが1つでも立っていたら無条件拒否。非危険物専用という構造的境界そのもの |
| 3 | **facility-permit-capacity-gate**(新規、この業態固有) | HARD | 施設の当該waste-classの日次許可容量を超過、または許可自体が無ければ拒否 |
| 4 | source-provenance-gate | HARD | 分類根拠の出典が許可クラスに無ければ拒否 |
| 5 | licensed-disclosure | HARD | 契約 tier 超過の開示は拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | bulk-volume gate | SOFT | 大口(1000kg以上)pickup は必ず人間承認 |
| 8 | dispute-request | SOFT(無条件) | 紛争は常に escalate |

**意図的に無い項目**: 危険物の取扱い・保管・輸送に対応するフィールドは
スキーマに一切存在しない。hazard-misclassification-gate の実行時チェック
(`hazard-flags` セット)は**二重の防御**であり、第一の防御は「そもそも
危険物処理の実装が存在しない」という構造そのもの。

### 3. Phase 0→3 + 恒久人間ゲート、default-phase=1 を実装当初から採用

`cloud-itonami-isic-6311`/`cloud-itonami-isic-7820` で発見された
「`:phase` を省略した呼び出し元が黙って最大自律性を得る」fail-open バグを、
本 actor では実装当初から回避(`default-phase` = 1)。`:dispute/request`
はどの phase の `:auto` 集合にも入らない構造的恒久ゲート。

### 4. Robotics premise: false

配車・スケジューリング・マニフェスト記録は書面/システム上の業務であり、
actor の境界の外に実際の収集作業(物理的作動)は存在する — が、それは
この actor の管轄外(operator の車両・人員が実行する)であるため、actor
自体の robotics premise は false とする。

## Consequences

- (+) `kotoba-lang/industry` registry の 3811 スロットが実装へ昇格
  (`M6910`・`isic-8291`・`isic-4690`・`isic-4610`・`isic-6311`・
  `isic-7820` に続く7件目)。
- (+) hazard-misclassification-gate・facility-permit-capacity-gate という、
  他の cloud-itonami actor に存在しない廃棄物収集業固有の HARD チェックを
  新設。
- (-) 実定法の危険物判定基準は3法域(米国 RCRA・EU Waste Framework
  Directive・日本 廃棄物処理法)のみカバー。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。

## 代替案と不採用理由

- **危険物処理も同一actorでカバー**: 危険物廃棄物は輸送・保管・処理の
  規制体系が全く異なり(特別管理産業廃棄物等)、同一 actor に含めると
  スコープが際限なく広がり、非危険物収集という単一業態の governance が
  希釈される。専用の別 actor(将来の ADR)に切り出す方針を採用。
- **hazard-misclassification-gate を SOFT にとどめる**: 危険物の誤配送は
  高確信のまま起こりうる失敗モードであり、SOFT では防げない。HARD が
  必須と判断した。

## References

- `90-docs/adr/2607113200-cloud-itonami-isic-3811-waste-collection-actor.md`
  (superproject ADR、本 ADR と対)
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
  (直接の手本、フリート標準パターン)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "3811" エントリ)
