# Activity Selection trong trình quan sát

Tải một trong hai tệp `backend/examples/greedy/ActivitySelectionArraySample.java` hoặc
`backend/examples/greedy/ActivitySelectionListSample.java` lên giao diện, rồi chọn chế độ
quan sát từng bước. Hai ví dụ sắp các khoảng tăng theo thời điểm kết thúc và chọn những
khoảng không giao nhau.

Hint `activity-selection` được cấp khi phân tích AST nối cùng tập đầu vào, ứng viên,
điều kiện chọn, cập nhật `lastEnd` và tác dụng lên kết quả. Tên biến không tham gia quyết
định. Metadata `activitySelection` ghi cách biểu diễn, hai vai trò mốc thời gian, chính
sách giao nhau và các vị trí AST. `HALF_OPEN` dùng `start >= lastEnd`; `CLOSED` dùng
`start > lastEnd` và cần một nhánh chọn ứng viên đầu tiên an toàn với toàn miền `int`.

`SOURCE_PROVEN` nghĩa là mã nguồn có thao tác sắp tăng theo `end` được chứng minh trước
vòng quét. `INPUT_PRECONDITION` nghĩa là bộ phân tích chưa thấy bằng chứng sắp, nên thứ tự
đầu vào là tiền điều kiện. `CONTRADICTED` ghi nhận thao tác sort không phù hợp. Những nhãn
này độc lập với ảnh chụp runtime. Trên giao diện, `PENDING` là quyết định trước vị trí
bytecode; `OBSERVED_AFTER_WRITE` chỉ xuất hiện sau ảnh chụp xác nhận cập nhật đúng trong
cùng thread, lần gọi và đối tượng đầu vào. `UNKNOWN`, `TRUNCATED` hoặc `VIOLATED` không
được diễn giải là đã tối ưu.

Phạm vi hiện tại: `int[][]` với hai cột hằng suy ra từ cách dùng, và `List<Interval>`
với `record` hoặc lớp cùng nguồn có trường `int final`; runtime chiếu `ArrayList` và
`LinkedList`. Vòng `for` và `while` phải quét từ chỉ số 0 đến hết tập bằng bước tăng 1;
enhanced `for` được hỗ trợ cho cả mảng và danh sách. Kết quả là bộ đếm hoặc danh sách được chọn.
Trong điều kiện chọn và cập nhật `lastEnd`, các bản sao cục bộ bất biến của hai mốc
và helper `private`/`static`/`final` cùng lớp chỉ trả về trực tiếp một mốc cũng
được nhận diện.
Comparator trừ hai mốc `int`, sort có điều kiện, thay đổi tập đầu vào sau sort, và
hiệu ứng không chứng minh được sẽ không nhận `SOURCE_PROVEN`. Khi thiếu ảnh chụp hoặc
dữ liệu bị cắt ngắn, phần biến runtime tổng quát vẫn hiển thị.
Khoảng nửa mở có `start == end` vẫn được quan sát như một ứng viên, nhưng giao diện
không kết luận tối ưu vì khoảng rỗng cần quy ước tương thích riêng.

Kiểm tra nhanh: với các khoảng `(1,3), (2,4), (3,5), (5,7)` theo chính sách nửa mở,
đầu ra đúng là 3 lựa chọn. Thử đổi tên biến và đổi vị trí hai cột ở ví dụ mảng, đồng
thời sửa các phép đọc `start`/`end` tương ứng; hint vẫn phải chỉ đúng vai trò hai cột.
