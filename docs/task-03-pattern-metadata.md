# Task 3: Metadata ID truc tiep tren algorithm hint

## Contract moi

AlgorithmHint giu cac field cu va them:

```json
{
  "patternAstNodeId": "ast-WhileStmt-5-5-10-5",
  "methodAstNodeId": "ast-MethodDeclaration-2-3-11-3",
  "variableDeclarationIds": {
    "array": "<array-declaration-id>",
    "left": "<left-declaration-id>",
    "right": "<right-declaration-id>",
    "mid": "<mid-declaration-id>"
  }
}
```

ID dung chung quy uoc AstAnalyzer.nodeId. Chung on dinh khi parse lai cung source,
khong phai ID ben vung sau khi sua code. Constructor cu van dung duoc, ID null
va map rong de giu tuong thich cac caller cu.

## VisualTraceBuilder

Hint moi: lookup loop truc tiep qua patternAstNodeId, kiem tra method ID, role keys
va declaration ID/name co trong analysis. Khong scan lai range de chon loop.
Hint ID sai/khong day du bi bo qua, khong fallback sang doan theo dong.

Hint legacy khong co pattern ID: fallback theo range duy nhat, dung lexical
bindings cua task 2. Nhieu loop cung range van bi tu choi o duong legacy.

Hai loop cung dong nay co ID rieng va duoc gan state dung loop theo AST point.
Dieu nay KHONG khac phuc line-only breakpoint cua JDI: runtime van co the khong
thu duoc tung statement/loop tren cung dong. UI chua thay doi.

## Review va test

- Test metadata: ID pattern/method va moi role khop khai bao trong analysis.
- API integration: JSON co ba field moi; cac field cu van ton tai.
- Visualization test: tung loop cung dong nhan dung hintIndex; legacy van fail closed.
- Toan bo test JDI/visualization cu phai pass de kiem tra tuong thich.

Chay `mvn test` trong backend voi JDK 17. Cac gioi han cu ve BEFORE_LOCATION,
range candidate, invocation identity va pattern coverage khong thay doi.

Ket qua cuoi task 1-3: **112 tests, 0 failures, 0 errors, 0 skipped**.
Gom 5 regression tests va 6 lexical binding tests moi; API/visualization tests
duoc mo rong. Truoc fix da ghi nhan test sum khac scope that bai, sau fix pass.
Review: khong thay loi chan trong pham vi da test. Chuan hoa bieu thuc van thuoc
task 4; resolver chu dong tu choi cac tham chieu chua ho tro.
