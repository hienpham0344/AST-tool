# Linear Search: kiểm tra nhận diện và trace

Tải `backend/examples/linearsearch/ArrayIndexSearchSample.java` hoặc
`backend/examples/linearsearch/ListContainsSearchSample.java` trong dashboard rồi mở runtime
steps. Mẫu đầu trả về chỉ số, mẫu sau trả về `boolean`. Chuyển từng bước để xem vị trí quét,
giá trị hiện tại, mục tiêu và trạng thái so sánh ứng viên.

`POST /api/debug/steps` bổ sung `algorithmHints[].linearSearch` với declaration IDs, AST ID
của phép so sánh/trả về và bằng chứng mã nguồn. Các bước trong vòng lặp có
`visualStates[].linearSearchFrame`. `MATCH` và `DIFFERENT` được suy từ ảnh chụp trước dòng
code: chúng không khẳng định nhánh `if` đã chạy. Thiếu biến, đối tượng đầu vào, phần tử hoặc
ảnh chụp bị cắt sẽ cho `UNKNOWN`/`TRUNCATED`. `POST /api/debug` vẫn trả mảng quan sát cũ.

Phiên này hỗ trợ quét tiến theo chỉ số trên `int[]` và `List<Integer>` bằng `for` hoặc `while`,
so sánh bằng với mục tiêu `int`, trả chỉ số hoặc `boolean`, và trả `-1`/`false` ngay sau vòng
lặp khi không tìm thấy. Đổi tên biến, đảo vế so sánh và đổi cách tăng chỉ số vẫn được nhận
diện khi giữ đúng quan hệ dữ liệu. `for-each`, tìm ngược, helper và luồng `break`/cờ kết quả
cần detector riêng; không có hint trong các mẫu này không có nghĩa code sai.
