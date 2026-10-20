# Two Pointers: kiểm tra nhận diện và trace

Chạy backend, mở `UI/dashboardPage.html`, tải lần lượt
`backend/examples/twopointers/OpposingArraySample.java` và
`backend/examples/twopointers/SameDirectionListSample.java`, rồi chọn xem biến runtime.
Dùng thanh bước để quan sát con trỏ, vị trí hiện tại, vùng giữa hai con trỏ
hoặc vùng đã quét. Bảng biến tổng quát vẫn hiển thị bên dưới.

`/api/debug/steps` trả `algorithmHints[].twoPointers` với `movement`,
`representation`, ID khai báo và `evidence`. Trong mỗi bước được liên kết,
`visualStates[].twoPointersFrame` mang chỉ số, giá trị quan sát được, delta,
`sourceEvidence`, `snapshotPhase` và `effectStatus`. `OPPOSING` dùng vai trò
`left`/`right`; `SAME_DIRECTION` dùng `scan`/`slow`. `PENDING` nghĩa là ảnh
chụp trước lệnh, `OBSERVED` là có ảnh chụp kế tiếp xác nhận, `UNKNOWN` là
chưa đủ dữ liệu, `TRUNCATED` là dữ liệu bị cắt, `VIOLATED` là chuyển động
quan sát được trái với mẫu. Đây là bằng chứng về cách duyệt, không chứng minh
kết quả bài toán đúng trên mọi đầu vào. `/api/debug` vẫn là mảng quan sát cũ.

Detector xét quan hệ chỉ số, điều kiện lặp, truy cập cặp phần tử và tiến triển
theo đường đi trong AST; tên biến không quyết định mẫu. Phạm vi hiện tại:
`int[]`, `List<Integer>` với biểu thức `get`/`set`, `for`/`while` và một số
`do/while` có guard; runtime chiếu `ArrayList<Integer>` và
`LinkedList<Integer>` tối đa 100 phần tử. Kiểu collection khác, alias bị gán
lại, lời gọi không phân giải được, nhánh không chứng minh được tiến triển và
dữ liệu thiếu sẽ bị từ chối hint chuyên biệt hoặc hiện `UNKNOWN`. Danh sách
có `null` không được suy thành giá trị số. Nếu hai phép cập nhật cùng một dòng
không thể tách thành ảnh chụp riêng, UI không xác nhận tác dụng trung gian.
