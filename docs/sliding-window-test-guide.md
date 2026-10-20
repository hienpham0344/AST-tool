# Sliding Window: kiểm tra nhận diện và trace

Từ `UI/dashboardPage.html`, tải lần lượt
`backend/examples/slidingwindow/FixedWindowArraySample.java` và
`backend/examples/slidingwindow/VariableWindowListSample.java`, rồi mở phần runtime steps.
Mẫu đầu hiển thị `FIXED_SIZE` trên `int[]`; mẫu sau hiển thị `VARIABLE_SIZE` trên
`List<Integer>`. Có thể kéo thanh Step hoặc dùng Play để xem cửa sổ, chỉ số,
accumulator và trạng thái hiệu ứng.

`POST /api/debug/steps` trả về `algorithmHints[].slidingWindow` với declaration IDs,
operation AST IDs và bằng chứng mã nguồn. Trong các bước thuộc vòng lặp,
`visualStates[].slidingWindowFrame` chứa khoảng ứng viên `[start, endExclusive)`,
giá trị thêm/bỏ và `effectStatus`. `PENDING` nghĩa là breakpoint hiện tại đứng
trước phép toán; `OBSERVED` chỉ xuất hiện sau ảnh chụp kế tiếp phù hợp trong cùng
thread, invocation và đối tượng đầu vào. `UNKNOWN` hoặc `TRUNCATED` nghĩa là dữ
liệu chưa đủ để kết luận. Khoảng hiển thị không tự chứng minh các phần tử đã được
đưa vào accumulator.

Detector hiện nhận mẫu cộng/trừ tổng có một con trỏ quét tăng từng bước, đầu vào
`int[]` hoặc `List<Integer>`, và phép bỏ phần tử được bảo vệ bằng điều kiện biên.
Đổi tên biến, dùng `for`/`while`, `+=`/`sum = sum + value` đều được hỗ trợ khi giữ
nguyên quan hệ dữ liệu. Mẫu có helper call, nhánh bỏ qua phép cộng, thay đổi đầu
vào, hoặc không chứng minh được điều kiện biên sẽ không nhận hint. `POST /api/debug`
giữ nguyên mảng quan sát cũ, không chứa metadata Sliding Window.
