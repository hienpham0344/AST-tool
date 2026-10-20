# Thiết kế BFS cho AST-tool

Trạng thái: người dùng đã duyệt thiết kế và yêu cầu triển khai ngày 2026-10-20.

## Mục tiêu
Nhận diện BFS từ liên kết cấu trúc AST và biến runtime, không dựa trên tên biến hoặc tên phương thức người dùng. Bổ sung hint và trace vào POST /api/debug/steps; frontend ở project khác dùng hợp đồng JSON. Giữ snapshot BEFORE_LOCATION và hành vi thuật toán hiện có.

## Phạm vi đợt đầu
Đồ thị có đỉnh nguyên 0..n-1, biểu diễn bằng int[][] dạng ma trận kề vuông (ô khác 0 là cạnh), hoặc List<List<Integer>> dạng danh sách kề. Hỗ trợ ArrayList/LinkedList chính xác cho các danh sách; không suy đoán cấu trúc của subclass hay collection tùy ý. Hàng đợi Queue<Integer>/Deque<Integer> có instance ArrayDeque hoặc LinkedList; visited là boolean[]. Duyệt từ một đỉnh nguồn; chỉ biểu diễn thành phần đến được, không tự chạy thêm các đỉnh khác. Hỗ trợ cạnh có hướng/vô hướng theo dữ liệu thực tế, chu trình, tự khuyên và cạnh trùng. Chưa hỗ trợ BFS lưới 2D, đỉnh dạng object/string, hàng đợi tự cài đặt, đánh dấu khi dequeue, và chứng minh đường đi ngắn nhất.

## Nhận diện AST
Detector riêng liên kết declaration IDs của graph, queue, visited, current và neighbor trong cùng callable. Chứng minh khởi tạo FIFO rỗng, đánh dấu và enqueue nguồn, vòng while/for kiểm tra queue còn phần tử, dequeue đầu và enqueue đuôi trên cùng queue. Chấp nhận add/offer/addLast/offerLast và poll/remove/pollFirst/removeFirst theo kiểu thực tế; không chấp nhận push/pop, enqueue đầu, pollLast hay PriorityQueue. Chứng minh vòng duyệt láng giềng của current, điều kiện chưa visited, đánh dấu visited trước enqueue đúng neighbor. Với ma trận phải chứng minh kiểm tra cạnh của graph[current][neighbor], vòng neighbor phủ miền đỉnh; với danh sách phải chứng minh lấy graph.get(current). Không chấp nhận ghi visited/queue bất thường, alias chưa chứng minh, nguồn dữ liệu khác hoặc guard làm enqueue/mark mất liên kết. Khi không đủ bằng chứng thì không phát hint, không đoán theo tên. Bảo toàn thứ tự ưu tiên detector hiện tại; BFS được thử trước các heuristic chung trên vòng chính, không phát hint riêng cho vòng láng giềng.

## API và runtime
Thêm metadata bfs (schemaVersion=1) vào AlgorithmHint và bfsFrame vào VisualState, nullable và bỏ khỏi JSON khi vắng mặt; giữ constructor tương thích. Metadata chứa representation, declarationIds, operationAstNodeIds và evidence. Frame gồm graph dạng danh sách đỉnh/cạnh, source, currentVertex, examiningEdge, queue theo thứ tự FIFO, visitedVertices và status/notes. Không thêm endpoint.

Projection được chọn theo hints và declaration IDs. Đọc field JDI, không gọi method trong target VM: ArrayDeque đọc elements/head/tail, LinkedList đọc size/first/next/item, danh sách kề và mảng lồng đọc có giới hạn. Dùng chung ngân sách hiện có; tối đa 100 đỉnh, 100 phần tử queue, 1000 cạnh và ngân sách tổng lồng nhau hữu hạn; phát TRUNCATED/UNKNOWN thay vì bịa dữ liệu. Kiểm tra null, kiểu, phần tử ngoài miền và object cycle. Giới hạn cụ thể có thể điều chỉnh để khớp ngân sách mapper hiện có nhưng phải ghi tài liệu và test. Phần graph không đọc đủ thì không khẳng định toàn bộ cạnh đã được quan sát.

Trace gắn với thread, invocation, hint và object identities của graph/queue/visited. Không nối trạng thái qua lần gọi hoặc object thay thế. BFS frame là dữ liệu quan sát được; current/neighbor có thể vắng trước khai báo hoặc khi ra khỏi scope. examiningEdge chỉ hiện tại vị trí AST có đủ current/neighbor hợp lệ. Sự kiện ENQUEUE, DEQUEUE, VISIT chỉ phát từ chênh lệch hai snapshot tương thích quan sát được; bỏ qua suy luận không đơn nghĩa hoặc dữ liệu cắt cụt. Không coi BEFORE_LOCATION là tác dụng của câu lệnh chưa chạy; snapshot cuối thiếu hậu quan sát không được dùng để bịa sự kiện kết thúc. Không phát nhãn COMPLETED chỉ vì thấy queue rỗng trước khi seed hoặc thiếu dữ liệu.

## Kiểm chứng và bàn giao
Test dương đổi tên biến, while/for, ArrayDeque/LinkedList, cả hai biểu diễn; test âm DFS stack, PriorityQueue, sai visited index/queue/neighbor, mark sau enqueue, queue reassignment và guard không liên quan. Test JDI projection giới hạn/queue wraparound/null; trace test BEFORE_LOCATION, phân tách invocation/thread, hàng đợi trống ban đầu, chu trình/cạnh trùng, dữ liệu thiếu/cắt cụt. Tích hợp API chạy fixture tự chứa cho từng biểu diễn, xác nhận runtime queue/visited khớp chương trình thực thi. Chạy test tập trung rồi toàn bộ suite bằng toolchain thực tế tìm được. Cập nhật README, guide BFS, fixtures và frontend-runtime-json-contract.md. Không sửa frontend ở project khác, không tự commit/push.

## Quyết định giao người triển khai
Tên class/record và cách tái sử dụng mapper/bindings hiện có; bố trí test, thứ tự các bước triển khai, mã trạng thái theo quy ước hiện hành. Không thêm dependency.
