# Frontend: DFS đệ quy và quay về caller

Hướng dẫn cho frontend ở project riêng. DFS đợt đầu dùng đỉnh int, boolean[]
visited, ma trận kề int[][] hoặc danh sách kề List<List<Integer>> chuẩn java.util.
Backend nhận diện cấu trúc mark/guard/recursive call, không phụ thuộc tên biến.
Bản đầu giới hạn method void với ba tham số vai trò, đệ quy trực tiếp không receiver
tường minh và dispatch không mơ hồ; overload hoặc method có thể override bị từ chối
trừ các trường hợp private/final/final class được chứng minh an toàn.

## Chạy fixture

Chạy backend theo [README](../README.md), gửi multipart field file:

```bash
curl -F 'file=@backend/examples/graph/DfsMatrixSample.java' \
  'http://localhost:8080/api/debug/steps'
curl -F 'file=@backend/examples/graph/DfsMatrixSample.java' \
  'http://localhost:8080/api/debug/steps?traceMode=OPERATIONS'
```

[Fixture matrix](../backend/examples/graph/DfsMatrixSample.java) có cạnh
0→1,0→2,1→2,2→0,2→3 và đỉnh 4 cô lập. Từ 0, thứ tự đi sâu là0,1,2,3;
visited cuối [true,true,true,true,false]. Chu trình2→0 bị guard visited chặn.
Thứ tự phụ thuộc thứ tự duyệt láng giềng trong source, không phải một thứ tự
backend tự sort cho đẹp hình. [Fixture list](../backend/examples/graph/DfsListSample.java)
dùng ArrayList và chạy với LINE_BREAKPOINT. Không mặc định fixture list sẽ chạy
được strict OPERATIONS: mode đó chưa hỗ trợ new object/library construction;
422 phải hiển thị rõ, không âm thầm fallback thành một trace kém chi tiết.

JSON capture thật đầy đủ của fixture matrix:
[LINE_BREAKPOINT](examples/dfs-line.json) và [OPERATIONS](examples/dfs-operations.json).
Các identity chỉ thuộc một lần chạy; không hard-code chúng hoặc số step vào UI.

## Bind vai trò theo AST và identity

algorithmHints type dfs có metadata dfs schemaVersion=1. declarationIds xác định
graph/visited/current/neighbor; operationAstNodeIds gắn mark/neighborLoop/guard/
recursiveCall vào source gốc. methodAstNodeId xác định hàm được nhận diện.
Hint là bằng chứng cấu trúc của method, không chứng minh nó đã được gọi hoặc
traversal đã hoàn tất. Đổi tên tham số không thay đổi ý nghĩa vai trò.

Mỗi lần vào DFS có invocationId riêng, nhưng các invocation có thể cùng tham
chiếu graph và visited. Giữ một object heap cho cùng identity, nhiều biến reference
ở các frame; không clone visited khi đi sâu và không xóa visited khi một frame
con kết thúc. Xóa đúng biến local theo instance/lifetime, không theo tên.

## Chạy từ main, đi sâu rồi quay lại

Dùng [operation trace](frontend-operation-trace-guide.md) khi cần từng thao tác.
Luôn phát theo sequence, không sort theo dòng hay vị trí khai báo method:

1. Bắt đầu tại main và chạy các khai báo/source operations thực tế.
2. BEFORE lời gọi explore giữ caller pending. Các step tiếp theo trong invocation
   con mới chuyển highlight vào hàm, dựng workspace tham số/local của con.
3. Ở BEFORE visited[vertex]=true, ô cờ vẫn giữ giá trị cũ; AFTER mới áp
   ARRAY_ELEMENT_WRITTEN đã quan sát. Không tự đánh visited từ lúc method vừa vào.
4. Duyệt điều kiện cạnh và !visited; highlight candidate trước khi gọi. Chỉ đi
   sâu khi trace thật có invocation con, không tính DFS lại bằng JavaScript.
5. Giữ workspace caller ở trạng thái tạm dừng khi child chạy. Các biến local
   tên giống nhau ở nhiều invocation là các entity khác nhau.
6. METHOD_EXIT xác nhận child thực sự rời hàm. Quay về caller và tiếp tục phase
   AFTER của lời gọi tương ứng; không nhảy thẳng về main khi mới thoát một mức.
7. Sau khi invocation DFS gốc kết thúc mới quay về phần còn lại trong main.

Trong fixture, local được tạo riêng mỗi lần gọi, finished xuất hiện sau vòng
láng giềng, và chúng bị hủy theo scope thực. Các dòng thường đó vẫn phải phát.
AFTER RETURN chỉ xác nhận expression return đã đánh giá; không thay METHOD_EXIT.

Call stack UI dựa context.threadId/invocationId/methodName/stackDepth và thứ tự
call/exit thực. stackDepth là độ sâu frame runtime, không phải một danh sách
đường đi DFS được backend suy ra. Nếu response thiếu dữ liệu cho một mức, hiển
thị thiếu thay vì tự chèn đỉnh vào đường đi.

## LINE_BREAKPOINT: đồ thị và visited quan sát được

Đọc mọi visualStates theo hintIndex; frame DFS dành riêng cho snapshot
BEFORE_LOCATION. Mark/call ở location hiện tại vẫn pending. visitedVertices
đến từ các cờ true thực đã capture, không phải lịch sử DFS mô phỏng. Không lấy
currentVertex làm bằng chứng đỉnh đã được mark nếu snapshot chưa chứa cờ đó.

Graph projection đọc từ runtime với giới hạn; matrix/list chứa đồ thị có hướng
đúng như source, không tự thêm cạnh ngược. Candidate edge không có nghĩa một
lời gọi mới đã diễn ra. Đỉnh đã visited có thể xuất hiện như láng giềng nhưng
sẽ bị source guard bỏ qua; highlight khác với nhánh recursion thực.

### Các field trong dfsFrame schemaVersion=1

| Field | Cách render |
| --- | --- |
| invocationId, stackDepth | Gắn workspace vào đúng lần gọi; độ sâu là context runtime thực. |
| graphObjectId, visitedObjectId | Identity của heap object dùng chung, không phải ID declaration. |
| representation | ADJACENCY_MATRIX hoặc ADJACENCY_LIST. |
| graph | Projection với vertexCount, edges[{from,to}], status, truncated; field chưa biết có thể vắng. |
| currentVertex, neighborVertex | Đỉnh hiện tại và láng giềng ở source site đang xét; neighbor có thể null ở header/code thường. |
| visitedVertices | Tập index có flag true đã capture, sắp theo index; không phải discovery order. |
| examiningEdge, edgeStatus | Cạnh quan sát được là CANDIDATE; null/UNKNOWN khi không đủ dữ liệu; NOT_APPLICABLE ngoài guard/call. |
| graphStatus, visitedStatus | AVAILABLE/UNKNOWN/TRUNCATED, là trạng thái authoritative của frame. |
| currentStatus, neighborStatus | AVAILABLE/UNKNOWN/OUT_OF_BOUNDS; neighbor có thể NOT_APPLICABLE. |
| markStatus | PENDING tại source mark khi flag chưa true; OBSERVED khi snapshot đã thấy flag true; UNKNOWN nếu thiếu bằng chứng. |
| callStatus | PENDING ở source call có cạnh và neighbor chưa visited; UNKNOWN hoặc NOT_APPLICABLE ở các trường hợp còn lại. |
| snapshotPhase, notes | BEFORE_LOCATION và giới hạn diễn giải snapshot. |

markStatus=OBSERVED chỉ xác nhận flag true trong snapshot; không chứng minh chính
invocation hiện tại vừa ghi cờ đó. Không phát thêm animation mark từ trạng thái
này. callStatus=PENDING không được push stack; đợi invocation con thật trong trace.
Frame state READY/PARTIAL/TRUNCATED không phải kết quả traversal hoàn tất.

Dùng graphStatus/visitedStatus của frame trước khi render payload. Builder có
thể hạ mức tin cậy của dữ liệu malformed; không bỏ qua trạng thái đó chỉ vì một
field con vẫn có giá trị. Legacy projector giới hạn 100 đỉnh, 1000 cạnh và 1000 lần đọc
mỗi projection; vượt giới hạn trả TRUNCATED. Projection visited phải có length
khớp vertexCount để frame gắn cờ vào các đỉnh; buffer dài hơn hoặc shape lỗi
trả UNKNOWN thay vì thêm các đỉnh ngoài đồ thị. Mode OPERATIONS dùng các ngân sách
raw snapshot và payload riêng đã mô tả trong hợp đồng operation.

Projection hiện tái sử dụng wire type bfs-graph và bfs-visited của bộ đọc đồ thị
dùng chung; tên projection này không phải hint BFS. Chọn renderer bằng hint type
và dfsFrame, không suy thuật toán từ tên visualType.

Line mode có thể không tách nhiều statement cùng một dòng. Muốn diễn hoạt rõ
mark, các biến local và chuyển hàm thì dùng OPERATIONS. Mode này giữ generic
steps/effects, không yêu cầu dfsFrame legacy trong response schemaVersion=2.

## Dữ liệu thiếu, giới hạn và phạm vi

Projection graph/visited UNKNOWN hoặc TRUNCATED phải hiển thị trạng thái tương
ứng. Không biến dữ liệu thiếu thành graph rỗng, visited false hoặc traversal
xong. Candidate vượt biên không được chuyển thành cạnh hợp lệ. Đỉnh cô lập hoặc
không tới được vẫn ở trạng thái chưa thăm; không tự chạy DFS từ đỉnh đó sau khi
main kết thúc, trừ khi source thật có gọi tiếp.

Bản đầu là DFS đệ quy có visited guard, không gồm iterative stack, cây không
visited, lưới 2D, visited ba màu, timestamp hoặc topological sort. Các biến thể
không đủ proof không có hint DFS; vẫn có thể xem generic trace nếu mode hỗ trợ.
List projection chỉ đọc cấu trúc collection được backend hỗ trợ, không gọi
get/size hay toString qua JDI để lấy dữ liệu. Không giả định mọi implementation
java.util.List đều đọc được; xem status runtime.

Với TRUNCATED/FAILED, giữ bước cuối xác nhận và lời gọi chưa có AFTER ở trạng
thái pending; không tự thêm return hay mark còn thiếu. Chương trình lỗi vẫn có
thể có hint tĩnh nhưng không có trace hoàn chỉnh.

Xem [hợp đồng JSON runtime](frontend-runtime-json-contract.md) và
[hướng dẫn operation trace](frontend-operation-trace-guide.md) để xử lý source
ranges, pause/step/back/seek, entity lifecycle và snapshot. Anchor AST của phép
mark/call có thể là con của statement trong step; dùng cây /api/ast/parse của
cùng source để map owner gần nhất, không parse format ID hoặc so số dòng đơn lẻ.
