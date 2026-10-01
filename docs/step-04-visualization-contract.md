# Buoc 4: Du lieu visualize cho tung execution step

Cap nhat task 3: hint moi dung pattern/method/declaration IDs truc tiep;
range matching mo ta duoi day chi con la fallback cho hint legacy.
Xem [metadata review](task-03-pattern-metadata.md).

## Muc tieu va pham vi

Backend them `visualStates` va `visualEvents` vao moi item trong `steps` cua
`POST /api/debug/steps`. Buoc nay chua thay doi giao dien.

Ho tro cac hint hien co: binary-search, two-pointers nguoc chieu, sliding-window
tinh tong (co bien left hoac windowSize). Chi ve mang mot chieu voi phan tu
scalar/null; mang nhieu chieu va object phuc tap fallback ve `variables` cu.

`variables`, `context`, `snapshotPhase`, `algorithmHints`, `warnings` giu nguyen.
`/api/debug` van tra mang ObservationResult nhu truoc.

## Luong xu ly

1. AstAnalyzer parse source; compiler/JDI thu cac snapshot BEFORE_LOCATION.
2. AlgorithmPatternAnalyzer tao hint tu AST.
3. VisualTraceBuilder ghep hint voi loop AST duy nhat co cung khoang dong.
4. Moi role (array/left/right/mid/accumulator/windowSize) duoc ghep voi ID khai bao
   AST. Bien runtime phai khop ca ten va ID, khong chi trung ten.
5. Chi tao state khi AST point nam trong loop, cung callable va context hop le.
   Loop co cung range dong khong phan biet duoc se bi bo qua.
6. Tao state moi tu snapshot hien tai; khong muon gia tri tu step truoc.
7. Tao event mo ta chenh lech giua hai state du dieu kien so sanh.

State co `id: hint-N`, `hintIndex: N` tro vao `algorithmHints[N]` trong response.
ID chi co y nghia trong response hien tai; khong phai ID invocation/object.
Moi step co the co nhieu state cho cac loop long nhau. FE khong nen mac dinh
chi co mot state, va phai ton trong danh sach rong khi ra khoi scope.

## Contract visualStates

Vi du day du cua mot state (rui ro/ghi chu cua thuat toan nam trong notes):

```json
{
  "id": "hint-0",
  "hintIndex": 0,
  "algorithm": "two-pointers",
  "visualPlan": "array-pointers",
  "status": "READY",
  "array": {
    "variableName": "a",
    "astNodeId": "<declaration-id>",
    "status": "AVAILABLE",
    "values": [1, 2, 3],
    "length": 3,
    "truncated": false
  },
  "pointers": {
    "left": {"variableName": "l", "astNodeId": "<left-id>", "index": 0, "status": "VALID"},
    "right": {"variableName": "r", "astNodeId": "<right-id>", "index": 2, "status": "VALID"}
  },
  "scalars": {},
  "range": {"kind": "POINTER_SPAN", "start": 0, "endExclusive": 3, "status": "VALID"},
  "notes": []
}
```

- `READY`: du du lieu de render cac role va range; khong co nghia thuat toan dung.
- `PARTIAL`: mang co du lieu nhung role thieu/ngoai bien/range chua xac dinh/array truncated.
- `UNAVAILABLE`: mang thieu, null, khong doc duoc hoac dang chua ho tro.

Array status: AVAILABLE, MISSING, NULL, UNAVAILABLE, UNSUPPORTED.
`length` la do dai that neu biet; `values` chi la prefix da capture.
Marker `{truncated:true,length:...}` tu serializer duoc tach ra, KHONG phai mot o mang.
`values` co the chua Java null; mang rong AVAILABLE khac voi mang NULL.

Pointer status:

| Status | FE xu ly |
| --- | --- |
| VALID | Co the gan con tro vao o index |
| NOT_CAPTURED | Index nam trong mang that nhung ngoai prefix da capture; khong ve vao o gia |
| OUT_OF_BOUNDS | Giu index de hien thi gia tri, khong gan vao o; khong tu clamp |
| MISSING | Bien chua visible hoac binding khong xac dinh; index null |
| NULL / UNAVAILABLE / INVALID | Khong ve con tro |
| ARRAY_UNAVAILABLE | Biet index nhung chua biet gioi han mang; khong ve con tro |

Chi chap nhan index nguyen trong mien Java int. Khong parse runtimeValue string
thanh so. Gia tri raw van con trong `variables`.

`scalars` chua accumulator/windowSize voi variableName, astNodeId, value, status.
windowSize phai la so nguyen duong; accumulator phai la Number huu han.

## Range va thoi diem snapshot

Moi range dung quy uoc `[start, endExclusive)`; status VALID, EMPTY, UNKNOWN hoac INVALID.
FE chi to range VALID, va chi to phan giao voi prefix array da capture.

- POINTER_SPAN: `[left, right + 1)` suy tu gia tri hai con tro hien tai.
- FIXED_SIZE_CANDIDATE: `[max(0, right - k + 1), right + 1)` neu k hop le.
  Khong tao bien left gia trong pointers.

Range la KHOANG CHI SO, khong phai chung minh cac phan tu da duoc cong vao accumulator.
Vi du truoc `total += nums[end]`, total chua bao gom nums[end], du candidate range
da co end. Notes se chua `RANGE_DOES_NOT_PROVE_ACCUMULATOR_MEMBERSHIP`.

Binary search chua co contract khai bao inclusive/exclusive bound trong hint.
POINTER_SPAN khong duoc goi la "tap ung vien chinh xac" voi moi bien the.
Neu right == array.length (half-open bound), right OUT_OF_BOUNDS va range UNKNOWN;
FE hien gia tri bound, khong ve no vao o mang. Notes:
`POINTER_SPAN_DOES_NOT_DECLARE_SEARCH_BOUND_CONVENTION`.

## Contract visualEvents

Moi event co stateId, type, target, fromSequence, toSequence, before, after.
Event mo ta KHAC BIET QUAN SAT GIUA SNAPSHOT, khong khang dinh statement gay ra no.

| Type | Y nghia |
| --- | --- |
| STATE_ENTERED | State moi xuat hien; render state hien tai, khong can replay |
| STATE_RESET | Co state cu nhung khong du dieu kien so sanh; thay bang state hien tai |
| STATE_LEFT | State khong con trong step hien tai; khong dong nghia ham da return |
| ARRAY_CHANGED | ArrayValue khac (gia tri/do dai/status); khong khang dinh cung object hoac swap nguyen tu |
| POINTER_CHANGED | Pointer khac, gom ca status; FE chi animate index neu ca hai dau VALID |
| VALUE_CHANGED | Scalar khac, gom ca status |
| RANGE_CHANGED | IndexRange khac; chi animate range hop le |

Voi cac event CHANGED, before/after la object dung contract cua field tuong ung.
Voi lifecycle event, before/after null; render tu visualStates hien tai.
STATE_ENTERED/STATE_RESET co fromSequence null. STATE_LEFT tro ve step truoc.
Khong tao step/event terminal gia khi VM ket thuc; step cuoi co the van chua state.

Chi so sanh hai step lien ke (sequence tang 1), cung thread/class/method/signature/
stackDepth va codeIndex tang. Khi codeIndex bang/giam, reset: do co the la back-edge
cua loop hoac lan goi moi o cung depth. Gia tri van day du, nhung mot so chuyen dong
qua vong lap se khong co event CHANGED. Khong suy ra invocation ID tu stackDepth.
Call/exception khong co breakpoint van co the nam giua hai snapshot; event khong
chung minh nguyen nhan thay doi hay tinh lien tuc cua invocation.

## Huong dan FE

1. Chon step, render toan bo visualStates tai step do (khong replay tu step 1).
2. Neu next lien ke, co the dung visualEvents de animate cac gia tri hop le.
3. Khi previous/seek/reset/context reset, dung snapshot dich lam nguon su that.
4. State rong/unsupported: fallback structured variables, khong giu state cu.
5. Hien warnings cua trace va status/notes khi can; khong ve index gia hay ket luan
   code da chay xong chi vi co snapshot.

## Chay thu

Khoi dong lai backend. Gui Java sample tu doc buoc 2 bang:

```powershell
curl.exe -s -F "file=@SearchSample.java" http://localhost:8080/api/debug/steps
```

Tai breakpoint truoc khai bao m, pointers.mid se MISSING.
Tai if so sanh nums[m], m da co gia tri va pointer VALID neu index nam trong mang.
Giao dien hien tai chua render cac field moi; xem JSON response de review.

Chay test: `mvn test` trong `backend` (JDK 17).

Ket qua cuoi: **101 tests, 0 failures, 0 errors, 0 skipped**. Buoc 4 them 12 unit
tests va 6 integration tests JDI; test API hien co duoc mo rong de kiem tra JSON.
Tam file Java lien quan da duoc format bang Spotless.

## Review buoc 4

- Kiem tra API cu: khong doi variables/context/phase/hints; /api/debug giu mang cu.
- Kiem tra snapshot: khong thuc thi them Java, khong invoke method cua debuggee,
  khong cap nhat pointer/accumulator som hon runtime.
- Kiem tra binding: dung AST declaration ID; same-line loop mo ho khong duoc ghep bua.
- Kiem tra bien: missing/null/unavailable/truncated/ngoai bien khac nhau ro rang.
- Kiem tra context: khong diff qua thread/depth/method/gap/rewind hay step ngoai scope.
- Test JDI that: binary search, dao mang hai con tro, fixed/variable sum window,
  mang 120 phan tu voi prefix 100, binary search half-open. API integration kiem tra
  JSON co state va event. Them test scalar khong hop le va doi method signature.
- Gioi han con lai: khong co invocation/object identity on dinh; range chi suy tu
  bien, chua phan tich chinh xac accumulator membership hay search-bound convention;
  frontend/animation se lam o buoc 5. Khong bao phu moi bien the LeetCode.
