# BFS: nhận diện và trace runtime

Gửi `backend/examples/graph/BfsMatrixSample.java` hoặc `BfsListSample.java`
đến `POST /api/debug/steps`. Frontend độc lập dùng
[frontend-runtime-json-contract.md](frontend-runtime-json-contract.md).

## Phạm vi nhận diện

Hint `bfs`, visual plan `graph-bfs`, liên kết biến bằng declaration ID:
`graph`, `queue`, `visited`, `current`, `neighbor` và `source` nếu nguồn là biến.
Nguồn literal được lưu trong `bfs.sourceLiteral`.

- Đỉnh nguyên `0..n-1`; `int[][]` ma trận kề vuông (khác 0 là cạnh), hoặc
  `List<List<Integer>>` danh sách kề.
- Queue được khởi tạo rỗng bằng `ArrayDeque` hoặc `LinkedList`; lấy đầu và
  thêm đuôi. Hỗ trợ `add/offer/addLast/offerLast` và
  `poll/remove/pollFirst/removeFirst` theo kiểu khai báo phù hợp.
- Mảng `boolean[]` mới; đánh dấu nguồn rồi enqueue ngay trước vòng chính.
  Vòng chính `while (!queue.isEmpty())` hoặc `for (; !queue.isEmpty();)`.
- Body chính gồm khai báo `int current = queue.poll()` (hoặc alias FIFO)
  và vòng láng giềng. Ma trận dùng vòng `for` từ 0 đến `graph.length` và
  guard `graph[current][neighbor] != 0 && !visited[neighbor]`.
  Danh sách dùng `for (int neighbor : graph.get(current))` và
  guard `!visited[neighbor]`. Trong guard: mark rồi enqueue đúng láng giềng.

Detector bảo thủ: biến đổi ngoài các cấu trúc trên có thể không có hint.
Chưa nhận body thêm logging, BFS lưới, stack/DFS, PriorityQueue, queue tùy biến,
mark lúc dequeue, alias hoặc cập nhật graph/queue/visited không chứng minh được.
Không dựa vào tên biến, hàm hay output. Không tự duyệt thành phần không đến được.

## Kiểm tra hai ví dụ

Cả hai có nguồn 0, đỉnh 4 không đến được, chu trình `3 -> 0` và self-loop ở 4.
Danh sách thêm cạnh trùng `0 -> 1`. Quan sát:

1. Metadata `schemaVersion = 1` theo đường dẫn
   `algorithmHints[i].bfs.schemaVersion`; representation khớp biểu diễn.
2. Frame `visualStates[].bfsFrame` có queue theo FIFO; có snapshot `[1, 2]`.
3. Các đỉnh đã thăm tăng đến `[0, 1, 2, 3]`, không chứa 4; cạnh trùng
   không khiến enqueue đỉnh 1 lần thứ hai.
4. Snapshot là `BEFORE_LOCATION`: dòng mark/enqueue hiện tại chưa đủ để khẳng
   định thao tác đã xong. `VISIT`, `ENQUEUE`, `DEQUEUE` cần hậu quan sát.
5. Queue rỗng không tự sinh `COMPLETED`; đỉnh hiện tại hoặc cạnh đang xét
   có thể null khi chưa khai báo/ngoài scope.

## Giới hạn dữ liệu

Projection chỉ đọc field JDI, không gọi method target VM. Collection runtime
phải là chính xác `ArrayList`/`LinkedList` cho danh sách, `ArrayDeque`/`LinkedList`
cho queue. Null, subclass, field không đọc được hoặc cấu trúc sai trả `UNKNOWN`.
Giới hạn tối đa 100 đỉnh, 100 phần tử queue, 1000 cạnh và ngân sách đọc lồng nhau
1000 giá trị mỗi projection; ma trận lớn có thể bị cắt dù thưa cạnh.
`TRUNCATED`/`UNKNOWN` không được dùng để khẳng định thao tác hoặc dữ liệu đầy đủ.

Sự kiện chỉ so sánh snapshot có cùng hint, thread, invocation và identities
của graph/queue/visited, cùng graph không đổi và dữ liệu đầy đủ. Bỏ qua thay đổi
không đơn nghĩa; không suy luận thứ tự các thao tác xảy ra giữa hai breakpoint.
