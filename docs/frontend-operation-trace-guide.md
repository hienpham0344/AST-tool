# Frontend: phát lại chương trình theo từng thao tác

## Bắt đầu bằng đúng chế độ API

Gửi file Java bằng multipart field `file` đến:

```bash
curl -F 'file=@backend/examples/trace/Sample.java' \
  'http://localhost:8080/api/debug/steps?traceMode=OPERATIONS'
```

Không tự đặt Content-Type khi gửi FormData. Không truyền traceMode vẫn là
LINE_BREAKPOINT cũ; chế độ cũ không đủ để tách các phép gán cùng dòng.
OPERATIONS dùng schemaVersion=2, granularity=SOURCE_OPERATION.

Không lọc steps theo algorithmHints hoặc theo các frame thuật toán. Mọi thao tác
source được hỗ trợ đều ở trong steps, gồm code thường, tạo biến, kiểm tra điều
kiện và cập nhật biến đếm. Một dòng có thể chứa nhiều operationId khác nhau.
Nhánh không thực thi không có step giả. Comment/dòng trống/dấu ngoặc không phải
lệnh Java; backend thể hiện vào/ra scope qua sự kiện riêng.

Bắt đầu với [fixture Java](../backend/examples/trace/Sample.java) và
[JSON capture thật đầy đủ](examples/operation-temp-swap-trace.json). Identity cụ thể
trong ví dụ chỉ thuộc một lần chạy, không hard-code các ID vào frontend.

## Chạy theo sequence, không gom ba phép gán thành swap

`steps` là các mốc quan sát có phase BEFORE, AFTER, ENTER, EXIT. Một thao tác
logic thường có BEFORE và AFTER cùng operationExecutionId, nên 3 phép gán có
thể có 6 mốc quan sát. Chúng vẫn là 3 thao tác, không phải 6 phép gán.

Không ghép các phase rồi phát AFTER sớm: một lời gọi hàm có thể có các bước
callee nằm giữa BEFORE và AFTER của caller. Luôn phát theo sequence. AFTER
RETURN nghĩa là biểu thức trả về đã đánh giá; chỉ METHOD_EXIT xác nhận hàm
thực sự rời stack. Các finally tổng hợp phục vụ trace bị ẩn khỏi source UI.

Mỗi step có snapshotId. variables là dữ liệu của frame đang được quan sát;
objects là cache projection heap có observedAtSequence. Object có thể được mang
qua các bước không đọc lại nó; status AVAILABLE nói về lần quan sát đó, không
khẳng định một snapshot nguyên tử của mọi frame tại sequence hiện tại. beforeSnapshotId
và afterSnapshotId giúp đối chiếu cặp thao tác; tra chúng qua map snapshotId ->
step. Chúng không phải lệnh tua toàn bộ UI về quá khứ khi AFTER đến, nhất là
khi caller/callee dùng chung một mảng.

Một lượt phát:

1. Highlight source.startLine/startColumn..endLine/endColumn từ source gốc.
   ENTER/EXIT có thể outline scope; ở EXIT dùng endLine/endColumn để nhấn dấu
   ngoặc đóng, không tô cả body như thể mọi dòng vừa được thực thi.
2. BEFORE: hiện trạng thái đang quan sát, giữ thao tác ở PENDING; chưa ghi đè.
3. AFTER: tạo entity trước khi copy/initialize nó, rồi đối chiếu/commit snapshot
   xác nhận. Với expression phức hợp hoặc thư viện ngoài source, các thay đổi
   cùng operation là nhóm quan sát; không đoán thứ tự bytecode giữa chúng.
4. ENTER/EXIT: xử lý vòng đời scope/loop và các entity bị tạo/hủy tương ứng.
5. Dù effects rỗng vẫn giữ step và highlight; đó có thể là code thường không
   làm đổi giá trị, kiểm tra điều kiện, lời gọi hàm hoặc dữ liệu chưa đọc được.

## Temp là một entity riêng, không phải nhãn hay phần tử mới của mảng

Với `a=[7,3], i=0, j=1` và code:

```java
int temp = a[i]; a[i] = a[j]; a[j] = temp;
```

Frontend phải dựng một ô biến tên `temp` riêng bên ngoài mảng:

| Thao tác | Điều phải nhìn thấy |
| --- | --- |
| Tạo temp | VARIABLE_CREATED tạo ô trống với valueStatus=UNINITIALIZED. |
| Khởi tạo temp | VALUE_COPIED từ ô a[0] đến temp; VARIABLE_INITIALIZED điền 7. a vẫn [7,3]. |
| Ghi a[i] | ARRAY_ELEMENT_WRITTEN thay a[0] từ 7 thành 3. a là [3,3], temp vẫn 7. |
| Ghi a[j] | Thay a[1] từ 3 thành 7. a là [3,7], temp vẫn 7. |
| Ra scope | VARIABLE_DESTROYED xóa ô temp, không xóa phần tử mảng. |

Hiệu ứng copy có thể dùng một bản sao hình ảnh của giá trị bay từ source đến
target; ô nguồn không bị lấy mất. Phân biệt copy scalar với alias reference:
copy reference tạo biến trỏ đến cùng object, không clone object.

VARIABLE_CREATED là bước dựng entity được chứng minh bởi declaration và scope;
không tuyên bố JVM đã dừng ở một bytecode cấp phát slot chưa khởi tạo.
Snapshot AFTER của declaration có thể đã chứa temp=7. Đừng dựng temp=7 trước
rồi bỏ qua các effects: render entity trống, animate copy/initialize, sau đó mới
commit snapshot AFTER. Không chèn temp vào mảng và không biến temp thành pointer
index. VALUE_COPIED chỉ có khi backend đủ bằng chứng nguồn; không tự suy từ tên
biến hoặc hai giá trị bằng nhau.

## Identity và vòng đời

Dùng variable.instanceId làm key entity, không dùng name. declarationId xác định
khai báo source; scopeInstanceId xác định lần kích hoạt scope. Cùng khai báo temp
ở lượt kế tiếp có declarationId cũ nhưng instanceId mới; biến temp cũ phải đã
bị hủy. Biến i khai báo trong for sống qua các lượt và chỉ mất khi thoát for.
Biến local khai báo trong body mất cuối body, kể cả continue/break/return.

Tên trùng ở scope khác không phải cùng entity. Khi vào hàm/đệ quy, giữ workspace
caller theo context và chỉ hiện frame đang hoạt động; không coi caller locals
đã biến mất chỉ vì snapshot hiện tại thuộc callee. Chỉ xóa theo event hủy có ID.

Mảng là object riêng. objects được tra bằng objectId; biến reference chỉ trỏ
đến object. ARRAY_ELEMENT_WRITTEN chỉ thay destination index. Khi hủy một biến
reference, không xóa object nếu còn alias; không đồng nhất variable death với
Java object destruction hay garbage collection.

## Trạng thái và các loại effect

variables[] có instanceId, declarationId, scopeInstanceId, name, declaredType,
valueStatus, value và objectId. UNINITIALIZED/UNAVAILABLE/TRUNCATED khác Java null:
chỉ AVAILABLE với value=null mới có thể biểu diễn Java null đã đọc được.
Array references dùng value.objectId, dữ liệu phần tử nằm trong objects.

- VARIABLE_CREATED: tạo entity; dùng after làm thông tin tên/kiểu/identity.
- VARIABLE_INITIALIZED: điền giá trị lần đầu, không tạo thêm entity nữa.
- VALUE_COPIED: source/target là descriptor VARIABLE hoặc ARRAY_ELEMENT.
- VARIABLE_WRITTEN: ghi giá trị đã xác nhận; before/after là trạng thái entity.
- VARIABLE_AVAILABILITY_CHANGED: khả năng quan sát đổi, không khẳng định code ghi.
- ARRAY_ELEMENT_WRITTEN: before/after là giá trị phần tử, target có objectId/index.
- OBJECT_PROJECTION_CHANGED: projection phức hợp đổi; không giả định một outer
  array slot thực sự đã bị gán hay nội suy các write không quan sát được.
- VARIABLE_DESTROYED: bỏ entity theo entityId; before giữ metadata cũ.
- SCOPE_ENTER/SCOPE_EXIT, LOOP_ENTER/LOOP_EXIT: hiển thị phạm vi/luồng điều khiển.
- CONDITION_EVALUATED: outcome boolean đã thực sự đánh giá, không chạy lại biểu thức.
- RETURN_VALUE: giá trị biểu thức return đã quan sát; chưa tự pop call stack.

## Điều khiển player

Play/step xử lý mỗi sequence đúng một lần. Có thể gom BEFORE/AFTER liền nhau
thành một thao tác UI nếu vẫn giữ đúng thứ tự và mọi effect; không gom qua
callee, scope event hay code thường. Tốc độ phát không làm thay đổi dữ liệu.

Back/seek nên dựng lại từ snapshot/checkpoint đã lưu và replay lifecycle events
đến sequence đích; không đảo animation rồi đoán giá trị. Khôi phục instanceId,
object alias và call frame chứ không chỉ text value. Mỗi lần tải trace mới reset
player state và các cache ID của trace trước.

## Lỗi và trace chưa hoàn tất

traceInfo.captureStatus COMPLETE xác nhận vòng thu thập kết thúc bình thường,
không bảo đảm mọi value projection đều đầy đủ. TRUNCATED có terminationReason
như MAX_MARKERS/TIMEOUT/MAX_VISIBLE_VARIABLES hoặc MAX_CAPTURE_VALUES,
MAX_CAPTURE_TEXT_CHARS, MAX_SNAPSHOT_VALUES, MAX_SNAPSHOT_TEXT_CHARS; FAILED có nguyên nhân như ngoại lệ
không được bắt, thiếu metadata JDI hoặc VM_TERMINATED_WITH_ACTIVE_INVOCATIONS
(kể cả System.exit/Runtime.halt khi method chưa kết thúc). Giữ bước cuối đã xác nhận, hiển thị
cảnh báo, không tự thêm final swap, return thành công hoặc kết thúc vòng lặp.
BEFORE chưa có AFTER giữ PENDING. Không tự coi biến thiếu là zero/null.

HTTP 422 với problems có range source nghĩa là operation mode chưa thể bảo toàn
cú pháp đó. Không im lặng chuyển sang LINE_BREAKPOINT rồi hiển thị như trace đầy
đủ. Có thể cho người dùng chọn rõ chế độ cũ, kèm giới hạn độ chi tiết.

Phạm vi cụ thể được chốt từ integration tests và mục giới hạn trong hợp đồng API.


## Shape wire cụ thể

Response có algorithmHints, steps, warnings và traceInfo. Những trường chính:

| Đối tượng | Trường |
| --- | --- |
| traceInfo | schemaVersion=2, mode=OPERATIONS, granularity=SOURCE_OPERATION, captureStatus, terminationReason, limits |
| step | sequence, operationExecutionId, operationId, phase, kind, source, context, snapshotId, beforeSnapshotId, afterSnapshotId, variables, objects, effects, status, conditionOutcome, scopeInstanceIds, attributes |
| variable | instanceId, declarationId, scopeInstanceId, name, declaredType, valueStatus, value, objectId |
| heap object | objectId, type, values, length, status, truncated, observedAtSequence |
| effect | type, entityId, before, after, source, target, status |

objectId và ID entity là string opaque; objects là map với key objectId. sequence
là thứ tự trong trace hiện tại. Không dùng ID của một response để nối response
khác. source chứa vị trí và code nguyên bản, không phải code được chèn marker.

Giá trị null ở before/after descriptor có thể nghĩa là chưa có entity ở đầu
chuyển tiếp. Để phân biệt với Java null của một biến, đọc valueStatus và value
của OperationVariable, không chỉ kiểm tra null trên effect.

Ví dụ effects thực tế của declaration temp (có thể gom thành một animation tạo
rồi điền ô temp, nhưng vẫn phải hiển thị ô riêng):

```json
{
  "sequence": 10,
  "phase": "AFTER",
  "kind": "DECLARATION",
  "effects": [
    {
      "type": "VARIABLE_CREATED",
      "entityId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
      "before": null,
      "after": {
        "instanceId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
        "declarationId": "ast-VariableDeclarator-3-9-3-17",
        "scopeInstanceId": "thread-1/invocation-1/scope-2",
        "name": "temp",
        "declaredType": "int",
        "valueStatus": "UNINITIALIZED",
        "value": null,
        "objectId": null
      },
      "source": {},
      "target": {
        "variableInstanceId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
        "kind": "VARIABLE",
        "declarationId": "ast-VariableDeclarator-3-9-3-17"
      },
      "status": "CONFIRMED"
    },
    {
      "type": "VALUE_COPIED",
      "entityId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
      "before": null,
      "after": 7,
      "source": {
        "objectId": "37",
        "kind": "ARRAY_ELEMENT",
        "index": 0
      },
      "target": {
        "variableInstanceId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
        "kind": "VARIABLE",
        "declarationId": "ast-VariableDeclarator-3-9-3-17"
      },
      "status": "CONFIRMED"
    },
    {
      "type": "VARIABLE_INITIALIZED",
      "entityId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
      "before": {
        "instanceId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
        "declarationId": "ast-VariableDeclarator-3-9-3-17",
        "scopeInstanceId": "thread-1/invocation-1/scope-2",
        "name": "temp",
        "declaredType": "int",
        "valueStatus": "UNINITIALIZED",
        "value": null,
        "objectId": null
      },
      "after": {
        "instanceId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
        "declarationId": "ast-VariableDeclarator-3-9-3-17",
        "scopeInstanceId": "thread-1/invocation-1/scope-2",
        "name": "temp",
        "declaredType": "int",
        "valueStatus": "AVAILABLE",
        "value": 7,
        "objectId": null
      },
      "source": {},
      "target": {
        "variableInstanceId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
        "kind": "VARIABLE",
        "declarationId": "ast-VariableDeclarator-3-9-3-17"
      },
      "status": "CONFIRMED"
    }
  ]
}
```

Ví dụ hai lần ghi mảng: nguồn copy và destination khác nhau.

```json
[
  {
    "sequence": 14,
    "code": "a[i]=a[j];",
    "effects": [
      {
        "type": "ARRAY_ELEMENT_WRITTEN",
        "entityId": "37",
        "before": 7,
        "after": 3,
        "source": {
          "objectId": "37",
          "kind": "ARRAY_ELEMENT",
          "index": 1
        },
        "target": {
          "objectId": "37",
          "kind": "ARRAY_ELEMENT",
          "index": 0
        },
        "status": "CONFIRMED"
      }
    ]
  },
  {
    "sequence": 16,
    "code": "a[j]=temp;",
    "effects": [
      {
        "type": "ARRAY_ELEMENT_WRITTEN",
        "entityId": "37",
        "before": 3,
        "after": 7,
        "source": {
          "variableInstanceId": "thread-1/invocation-1/scope-2/ast-VariableDeclarator-3-9-3-17",
          "kind": "VARIABLE",
          "declarationId": "ast-VariableDeclarator-3-9-3-17"
        },
        "target": {
          "objectId": "37",
          "kind": "ARRAY_ELEMENT",
          "index": 1
        },
        "status": "CONFIRMED"
      }
    ]
  }
]
```


## Phạm vi hiện tại và cách xử lý thiếu dữ liệu

Chế độ này hỗ trợ lớp Java17 có main, locals/arrays, block, if/else, các vòng
for/while/do-while/enhanced-for, method source thường và đệ quy, return/break/continue.
Một source operation là khai báo/phép gán/call/condition hoặc pha header; không
phải từng phép toán số học hay bytecode. Nhiều statement cùng dòng được tách;
hiệu ứng bên trong một expression không được gán thứ tự giả từ snapshot diff.

Field state chưa được chiếu theo operation, nên source có field bị từ chối rõ
bằng 422 thay vì bỏ qua biến đó. Constructor tường minh, new object, lambda,
method reference, nested type, user try/catch/finally, switch, label, synchronized,
throw/assert, pattern instanceof và các biến thể chưa bảo toàn được ngữ nghĩa
đều có lỗi coverage kèm range. Nested constant do-while cần suy luận completion
khác literal cũng bị từ chối. Đọc problems từ response; không dùng danh sách
này để tự đoán fallback hoặc quyết định backend có chạy được một file cụ thể.

Library internals không có trong uploaded source không được giả làm các step
source của người dùng. Một call có thể làm đổi nhiều giá trị; khi không có bằng
chứng thứ tự nội bộ, chỉ phát nhóm thay đổi đã quan sát và commit snapshot.

Giới hạn marker/thời gian/số biến hiển thị nằm trong traceInfo.limits. Projection
mảng giữ giới hạn hiện có (100 phần tử mỗi mảng và ngân sách lồng nhau). Cả
raw captures và snapshots có ngân sách tích lũy riêng: 500.000 giá trị và
4.000.000 ký tự text, công bố bằng maxCaptureValues/maxCaptureTextChars và
maxSnapshotValues/maxSnapshotTextChars. Mỗi lần dữ liệu xuất hiện lại đều được
tính, gồm variables, objects, effects before/after và source code. Backend
loại capture/step vượt ngân sách, giữ prefix đã xác nhận và báo TRUNCATED; đây
là giới hạn payload được giữ lại, không phải giới hạn heap của JVM target.

Cache objects chỉ giữ projection còn được tham chiếu từ các frame đang sống,
kể cả caller tạm dừng, alias và nested objectId. Reference không đọc được giữ
identity cuối biết với projection UNKNOWN cho đến khi quan sát lại hoặc hết
lifetime. Object biến mất khỏi cache là chính sách giữ projection, không phải
sự kiện JVM garbage collection. Snapshot cũ vẫn giữ dữ liệu để back/seek. Object
UNKNOWN/TRUNCATED không được dùng để nội suy các phần tử chưa đọc được. Nếu
projection một mảng bị lỗi, trạng thái cũ phải coi UNKNOWN chứ không mặc định mảng
vẫn nguyên. observedAtSequence chỉ ra lần đọc gần nhất.

## Checklist nghiệm thu player

- Chạy fixture: thấy ô temp riêng có tên, kiểu và giá trị 7; ô a[0] vẫn giữ 7
  trong lúc copy vào temp. Thấy code ordinary được highlight như các bước khác.
- Sau write đầu: a=[3,3], temp=7; sau write thứ hai: a=[3,7], temp=7.
- SCOPE_EXIT hủy temp và ordinary, giữ a/i/j của scope ngoài. Thoát method mới
  hủy các biến còn lại. Không nhầm hai biến khác nhau cùng tên.
- Với vòng lặp: body-local temp tạo lại từng lượt; for-init i giữ qua các lượt,
  continue vẫn đi qua update; break/return chỉ hiển thị các bước thực thi thật.
- Với call/recursion: caller BEFORE -> callee steps -> caller AFTER, không áp
  kết quả caller trước khi phát callee.
- Với trace bị cắt hoặc value không đọc được: player dừng đúng bước xác nhận,
  hiển thị trạng thái thiếu, không tự hoàn tất phần còn lại.

## Prefix Sum trong operation trace

Nếu algorithmHints có type=prefix-sum, dùng declarationIds và operationAstNodeIds
trong prefixSum để đặt nhãn hai hàng nguồn/prefix, highlight addends và ô đích.
Vẫn phát mọi step theo sequence: BEFORE giữ đích pending, AFTER mới áp write
được xác nhận. Không thay các recurrence bằng một animation xây cả mảng.
Zero chưa tính và kết quả đã tính bằng zero phải được trình bày khác nhau.

Xem [hướng dẫn Prefix Sum](prefix-sum-frontend-guide.md) để tích hợp hai layout,
query và fixture. Mode này giữ schemaVersion=2; không đòi prefixSumFrame legacy.

## DFS đệ quy

Hint `dfs` bổ sung ngữ nghĩa đồ thị cho các step thật, không thay trace bằng một
mô phỏng DFS. Bắt đầu ở main; khi source gọi DFS mới chuyển sang invocation con.
Giữ caller đang tạm dừng, chỉ quay lại sau METHOD_EXIT của con rồi tiếp tục
AFTER tương ứng. Nhiều frame cùng tham chiếu visited là một heap object dùng chung.

[Hướng dẫn DFS](dfs-frontend-guide.md) giải thích graph/visited, edge pending,
local lifetime, matrix operation fixture và các giới hạn. Không lọc bỏ code thường.
