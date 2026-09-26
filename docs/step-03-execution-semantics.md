# Buoc 3: Y nghia cua execution step

## Ket luan chinh

Mot step la mot lan JDI breakpoint duoc hit, voi snapshot local variables cua
frame dang dung. Snapshot duoc lay TRUOC bytecode tai location do chay.
Khong duoc coi no la trang thai SAU cau lenh dang highlight.

Co so: [JDK 17 BreakpointEvent](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.jdi/com/sun/jdi/event/BreakpointEvent.html).

## Contract API

`POST /api/debug/steps` giu `steps`, `warnings`, `algorithmHints`.
Moi step giu cac field cu va them:

```json
{
  "snapshotPhase": "BEFORE_LOCATION",
  "granularity": "LINE_BREAKPOINT",
  "context": {
    "threadId": 1,
    "threadName": "main",
    "className": "TimingSample",
    "methodName": "main",
    "methodSignature": "([Ljava/lang/String;)V",
    "stackDepth": 1,
    "codeIndex": 2
  }
}
```

Day la vi du metadata, threadId va codeIndex phu thuoc debug run/compiler.

| Field | Y nghia |
| --- | --- |
| sequence | Thu tu thu nhan event trong mot request, bat dau tu 1; khong phai dong code hay so vong lap |
| lineNumber | Dong runtime do JDI bao; -1 neu khong co thong tin dong |
| startLine/endLine/startColumn/endColumn/code | Pham vi/text cua AST point duoc chon; khong phai vi tri bytecode chinh xac |
| statementAstNodeId | ID AST point; co the lap lai o nhieu step |
| variables | Cac locals JDI thay duoc trong frame hien tai, khong phai moi bien trong chuong trinh |
| snapshotPhase | BEFORE_LOCATION: truoc bytecode location, khong cam ket truoc toan bo statement |
| granularity | LINE_BREAKPOINT: breakpoint duoc chon bang debug line table |
| context.threadId | Dinh danh thread chi trong debug run hien tai |
| context.methodSignature | JVM descriptor, phan biet method overload |
| context.stackDepth | Tong so frame JDI trong thread tai breakpoint; khong phai ID lan goi ham |
| context.codeIndex | Vi tri bytecode trong method, khong phai chi so source |

`ObservationResult.lineNumber` nay dung dong runtime, cung dong voi step.
Viec ghep local voi khai bao AST cung dung dong runtime thay vi dong bat dau AST.
API `/api/debug` van tra mang phang; endpoint nay khong co metadata step moi.

## Vi du doi chieu

```java
public class TimingSample {
  public static void main(String[] args) {
    int x = 1;
    x = 2;
    System.out.println(x);
    x = 3;
  }
}
```

| Dong breakpoint | Gia tri x trong snapshot |
| --- | --- |
| 3: int x = 1 | Chua xuat hien trong visible locals |
| 4: x = 2 | 1 |
| 5: println(x) | 2 |
| 6: x = 3 | 2 |

Khong co snapshot terminal tu dong voi x=3. Buoc cuoi KHONG dong nghia voi
trang thai cuoi chuong trinh. Khong tao them snapshot sau return/VM death.

## Vong lap, scope va goi ham

- Moi lan hit body loop tao mot step moi; ID AST co the giong nhau, sequence khac nhau.
- `for` header co initialization/condition/update tren cung dong. Mapper hien chi
  dat breakpoint tai location dau tien tim duoc; khong cam ket thu du moi lan
  condition/update. Khong dung so lan hit header de dem so vong lap.
- Bien chua khai bao/khong con visible se vang mat. FE khong duoc tu gan 0/null
  hay giu gia tri cu khi bien da ra khoi scope.
- `visualType: null` la gia tri Java null; `unavailable` la doc gia tri that bai;
  ca hai khac voi bien khong co trong danh sach.
- Tai call-site la snapshot truoc call; cac step trong callee chi chua locals
  cua callee. Tai breakpoint tiep theo cua caller moi co the thay ket qua call.
- De quy co cung method va AST ID nhung stackDepth khac nhau. Cap thread/depth
  khong phai invocation ID ben vung: cac lan goi noi tiep co the dung lai depth.

## Nhieu cau lenh cung dong va gioi han

`x = 1; x = 2;` tren mot dong chi co mot location dau tien duoc mapper chon.
Mapper deduplicate va tra warning `same JDI location`; khong tao hai step gia.
AST code/range chi la point dai dien, khong chung minh rieng statement do sap chay.

Chi class target hien tai duoc cai breakpoint; khong cam ket trace vao class phu,
thu vien hay nested class. Khong co event entry/exit/exception rieng.
Timeout/disconnect co the tao trace thieu; FE phai hien warnings.
VM ket thuc khong tu chung minh chuong trinh thanh cong (chua co exit status contract).
Breakpoint chi suspend event thread; shared objects co the thay doi boi thread khac.
Sequence la thu tu collector, khong phai thu tu nhan qua toan cuc giua cac thread.

## Cach FE su dung o buoc tiep theo

Render dung snapshot cua step dang chon. Khong gan nhan "da thuc thi xong dong nay".
Khong suy dien delta giua hai step la tac dong cua dung mot statement: co the co
call, bytecode khong duoc trace, hoac thread khac o giua.
Kiem tra context truoc khi so sanh locals. Chua dung stackDepth lam invocation ID.
Buoc 3 chua them visualStates/visualEvents hay thay doi giao dien.

## Kiem tra va review

Chay `mvn test` trong `backend` voi JDK 17.

Ket qua: 83 tests, 0 failures, 0 errors, 0 skipped. Bon test integration moi
chay JDI that; mot unit test moi kiem tra dong runtime trong variable mapping.

- `ExecutionSemanticsIntegrationTest`: compile va chay Java that qua JDI; kiem tra
  before-assignment, sequence, khong tao terminal snapshot, loop/body/scope,
  recursive frames, return ve caller, va nhieu statement cung dong.
- `VariableMapperTest`: dong runtime khac dong bat dau AST van ghep dung khai bao.
- `MultipartApiIntegrationTest`: JSON co snapshotPhase, granularity va context;
  cac field cu va endpoint legacy van hoat dong.

Review nhanh bang UI upload sample tren, mo response `/api/debug/steps` de doi
chieu bang gia tri. Can khoi dong lai backend de nap code moi.
