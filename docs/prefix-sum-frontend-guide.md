# Frontend: visualize Prefix Sum

Hướng dẫn dành cho frontend ở project riêng, cho metadata Prefix Sum schemaVersion=1.
API có hai chế độ: LINE_BREAKPOINT mặc định với BEFORE_LOCATION và OPERATIONS
schemaVersion=2. Không dùng ngữ nghĩa phase của chế độ này cho chế độ kia.

## Chạy ví dụ đầu tiên

Chạy backend theo [README](../README.md). Từ thư mục gốc repository:

```bash
curl -F 'file=@backend/examples/prefixsum/PrefixSumSentinelSample.java' \
  'http://localhost:8080/api/debug/steps'
curl -F 'file=@backend/examples/prefixsum/PrefixSumSentinelSample.java' \
  'http://localhost:8080/api/debug/steps?traceMode=OPERATIONS'
```

Field multipart là file; để FormData tự tạo Content-Type/boundary. Không có hint
không có nghĩa Java chạy sai; đó có thể là biến thể chưa được detector chứng minh.

[Ví dụ sentinel](../backend/examples/prefixsum/PrefixSumSentinelSample.java) có
mảng nguồn [2,-1,3,0], mảng prefix cuối [0,2,1,4,4]. Query left=1, right=3 lấy
prefix[4]-prefix[1]=4-2=2. Code ordinary trong vòng lặp vẫn phải được phát.
[Ví dụ cùng độ dài](../backend/examples/prefixsum/PrefixSumSameLengthSample.java)
dùng long[] và while, mảng kết quả [2,1,4,4].

JSON capture thật đầy đủ cho fixture sentinel:
[LINE_BREAKPOINT](examples/prefix-sum-line.json) và
[OPERATIONS](examples/prefix-sum-operations.json). Bản line có 15 snapshots,
bản operation có 61 mốc phase; không hard-code số bước hoặc identity vào player.

## Hiểu hai cách đánh chỉ số

Dựng hai hàng riêng: nguồn và prefix. Biến reference trỏ đến object; không clone
object dựa vào tên biến. Bind hint bằng declaration ID; bind entity runtime theo
identity được trả về, không theo giá trị bằng nhau hoặc tên quen thuộc.

- ZERO_SENTINEL: prefix có n+1 ô, prefix[0]=0; bước i đọc prefix[i] và data[i],
  ghi prefix[i+1]. Query đoạn đóng [l,r] đọc prefix[r+1] và prefix[l].
- SAME_LENGTH: prefix có n ô; seed copy data[0] sang prefix[0]. Bước i bắt đầu
  từ 1, đọc prefix[i-1] và data[i], ghi prefix[i]. Không áp công thức query
  sentinel cho layout này.

Ô primitive vừa cấp phát có thể chứa 0 nhưng chưa được vòng lặp xây dựng.
Tô màu đã tính chỉ theo bằng chứng tiến độ; không dựa vào value != 0. Kết quả
đã tính bằng 0 vẫn là kết quả hợp lệ. Với mảng rỗng, sentinel có đúng ô 0;
layout SAME_LENGTH cần seed data[0], nên chính source sẽ lỗi nếu mảng rỗng.
Backend không tự sửa chương trình người dùng.

## Đọc metadata nhận diện

Tìm algorithmHints có type=prefix-sum, visualPlan=prefix-arrays và prefixSum.
Map declarationIds chứa data/prefix/index. Map operationAstNodeIds chứa
allocation/seed/recurrence/loop/indexUpdate, đều là ID AST của source gốc.
Implicit seed trỏ vào khai báo cấp phát output; explicit seed là AssignExpr.
Query descriptor chứa expressionAstNodeId, leftDeclarationId/rightDeclarationId
và leftExpressionAstNodeId/rightExpressionAstNodeId. Endpoint là biến int được
ràng buộc theo declaration; đây không phải một trình đánh giá expression tùy ý.

Hint chứng minh cấu trúc, không chứa kết quả runtime. Không chạy thuật toán bằng
JavaScript để thay thế trace backend. Thuật toán nhận diện không phụ thuộc tên
biến; frontend có thể đặt nhãn theo role và giữ tên source để người học đối chiếu.

### Ánh xạ anchor expression sang operation source

Không yêu cầu operationAstNodeIds.recurrence bằng step.source.astNodeId: metadata
trỏ vào AssignExpr, còn operation thường trỏ ExpressionStmt bao ngoài. Tương tự,
query expression là BinaryExpr nhưng operation khai báo total trỏ VariableDeclarator.

Nếu frontend cần map chính xác anchor, gửi cùng file nguyên bản đến
`POST /api/ast/parse` (multipart field file). Response có `{success,data,error}`;
`data` là cây với astNodeId, type, startLine/endLine và children. Lập map node/parent
bằng astNodeId, tìm anchor trong cây, rồi đi lên owner gần nhất có type Expression,
VariableDeclaration hoặc Return. So owner.astNodeId với step.source.astNodeId;
không đi tiếp lên For/While vì header bao cả body nhưng không phải phép ghi.
Không parse format chuỗi ID hoặc chỉ so startLine: nhiều thao tác có thể cùng dòng.
Nếu không có mapping đủ chắc, giữ highlight source của step và không đoán anchor.

Với fixture hiện tại, recurrence AssignExpr ở dòng 7 là con của ExpressionStmt;
query BinaryExpr ở dòng 11 nằm trong VariableDeclarator total. API AST và trace
phải dùng đúng cùng source; sửa file thì tải lại cả metadata và trace.

## Đọc frame trong LINE_BREAKPOINT

Trong mỗi step, duyệt visualStates và chọn đúng hintIndex. prefixSumFrame có
schemaVersion=1 và snapshotPhase=BEFORE_LOCATION. visualState.array là mảng nguồn;
hai projection đầy đủ nằm trong frame.source và frame.result.

| Nhóm field | Cách dùng |
| --- | --- |
| representation, invocationId | Chọn layout, giữ workspace từng invocation. |
| sourceArrayObjectId, resultArrayObjectId | Gắn hai projection vào đúng object runtime; identity là opaque ID. |
| source, result | Mỗi projection có variableName, astNodeId, status, values, length, truncated. |
| currentIndex, sourceIndex | Chỉ số quét và ô nguồn ứng viên. |
| previousPrefixIndex, targetIndex | Ô prefix dùng làm addend và ô đích của recurrence. |
| sourceValue, previousPrefixValue, targetValue | Giá trị đã quan sát; targetValue ở BEFORE vẫn là giá trị trước write. |
| recurrenceStatus | PENDING chỉ highlight đích; UNKNOWN/OUT_OF_BOUNDS/NOT_CAPTURED không cho phép animation ghi giả. |
| seedStatus | PENDING trước seed; CONFIRMED khi control flow và snapshot đủ bằng chứng. |
| confirmedPrefixEndExclusive | Vùng [0,end) được chứng minh đã xây dựng; null nghĩa chưa đủ bằng chứng. |
| confirmedCells | Chỉ các ô đã capture trong vùng đó, gồm index/value/status. Không điền ô thiếu bằng phép cộng frontend. |
| progressStatus | CONFIRMED, UNKNOWN hoặc TRUNCATED; tách riêng mức đầy đủ projection. |
| query | Query đang quan sát với endpoint, chỉ số prefix, addends, result và status. Có thể null. |

recurrenceStatus=NOT_AT_RECURRENCE nghĩa location không phải phép ghi đó.
progressStatus=CONFIRMED chỉ nói vùng prefix tại snapshot đã đủ bằng chứng;
không có nghĩa toàn bộ vòng lặp hoặc chương trình đã hoàn tất. Ngay khi mảng mới
được tạo, các ô primitive đều có giá trị nhưng chưa thành confirmedCells.

Query PENDING có result=null. OBSERVED chỉ dùng scalar thực ở location sau
phép gán đơn/khởi tạo có thể chứng minh; không lấy accumulator sau `+=` làm kết
quả của riêng phép trừ, hoặc dùng endpoint đã bị query ghi đè để suy lại operand.
Khai báo nhiều biến trong cùng statement có thể chứa initializer làm đổi endpoint
hoặc result; backend không dựng query frame cho statement đó (query=null), kể cả
metadata vẫn giữ expression anchor. Nếu không đủ bằng chứng, giữ UNKNOWN thay vì
tự đánh giá biểu thức. Query cũng
có thể OUT_OF_BOUNDS/NOT_CAPTURED khi index hoặc projection không đủ.

Frame line mode không tách được nhiều phép gán trên cùng dòng; cần diễn hoạt
chi tiết từng thao tác thì dùng OPERATIONS. Không coi chuyển sang location mới
là bằng chứng mọi phép tính có cùng dòng đều đã được quan sát riêng.

## Phát từng thao tác trong OPERATIONS

Dùng [hợp đồng operation trace](frontend-operation-trace-guide.md). Giữ toàn bộ
steps theo sequence, kể cả khai báo scalar, vòng lặp và code thường. Không lọc
chỉ các node seed/recurrence/query; chúng là anchor để bổ sung highlight.

1. Tạo entity mảng sau khi runtime xác nhận allocation. Giữ các ô chưa xây dựng
   khác màu với vùng prefix đã được xác nhận. Zero sentinel có vai trò riêng.
2. Ở BEFORE recurrence, highlight hai ô nguồn của phép cộng và ô đích pending.
   Chưa điền hoặc tô ô đích hoàn tất.
3. Ở AFTER, xử lý ARRAY_ELEMENT_WRITTEN rồi commit snapshot. Với ví dụ sentinel,
   lần lượt thấy [0,2,0,0,0], [0,2,1,0,0], [0,2,1,4,0], [0,2,1,4,4].
4. Khi query được thực thi, highlight hai ô prefix cần trừ. Phát khai báo/ghi
   biến total bằng VARIABLE_CREATED/INITIALIZED/WRITTEN thực tế. BEFORE query
   không phải kết quả đã được gán.
5. Xử lý biến cursor/ordinary theo vòng đời thực; ordinary biến mất cuối body
   và được tạo entity mới lượt sau. Không tự tạo một bước SWAP/build-prefix.

Generic operation steps là nguồn dữ liệu cho mode này; không đòi visualStates
legacy trong schemaVersion=2. Các effects cùng expression phức hợp là nhóm thay
đổi quan sát được, không suy diễn thứ tự bytecode chưa được backend ghi nhận.

## Trạng thái thiếu dữ liệu và số nguyên

Không có projection, identity hoặc index hợp lệ thì hiển thị chưa biết; không
đổi thành 0, mảng rỗng hay kết quả đã tính. Mảng bị truncation chỉ được hiển thị
phần đã chụp; không tự tính các ô bị cắt. Query ngoài biên không có kết quả giả.
Khi traceInfo báo TRUNCATED/FAILED, giữ bước cuối xác nhận và không tự hoàn tất.

Phép cộng/trừ tuân theo Java int/long, kể cả overflow; ví dụ 2147483647+1 của
int cho -2147483648. Không dùng số toán học tự tính để thay giá trị snapshot.
JSON có thể chứa long ngoài miền nguyên chính xác của JavaScript Number;
frontend cần parse số nguyên không mất độ chính xác trước khi render/tính toán,
không chuyển qua Number rồi mới đổi sang BigInt.

## Phạm vi và xử lý biến thể chưa nhận diện

Đợt đầu hỗ trợ mảng int[]/long[] riêng biệt, output local cấp phát mới đúng kích
thước, chỉ số int đi lên một đơn vị trong for/while. Source là parameter hoặc
mảng local khởi tạo trực tiếp. Seed và recurrence phải được chứng minh theo
control flow. Không nhận inplace/suffix/2D/product/XOR, alias/factory input không
chứng minh được, cập nhật conditional, sửa nguồn hoặc output ngoài công thức.
Query hiện giới hạn dạng sentinel với endpoint biến int và expression trực tiếp
sau loop trong method; không nhận các công thức query cùng độ dài hoặc query loop.

Nếu Java compile lỗi, đọc lỗi API thay vì dựng trace. Nếu thiếu hint, vẫn có thể
phát generic OPERATIONS cho source được mode đó hỗ trợ. Nếu operation coverage
trả 422, hiển thị problems/range và cho người dùng chọn rõ mode khác nếu cần.

Xem [hợp đồng JSON chính](frontend-runtime-json-contract.md) và
[hướng dẫn operation trace](frontend-operation-trace-guide.md) để xử lý entity,
source ranges, snapshot, frame, pause/step/back/seek và trace chưa hoàn tất.
