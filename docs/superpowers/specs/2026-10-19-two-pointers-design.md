# Semantic Two Pointers Recognition Design

## Mục tiêu và phạm vi

Nhận diện họ two pointers trong Java 17 khi đổi tên biến, dạng vòng lặp,
cú pháp cập nhật, hoặc cách truy cập dữ liệu. Kết quả là một gợi ý về **mẫu
duyệt**, không phải chứng minh bài toán cụ thể đã giải đúng. Đợt này gồm hai
kiểu: hai con trỏ đi từ hai đầu vào giữa và hai con trỏ đi cùng chiều, trên
`int[]` và `List<Integer>`. Các đợt sliding window, greedy khác, và duyệt/tìm
kiếm dùng cùng nguyên tắc tách bằng chứng AST khỏi quan sát JDI, nhưng có spec
riêng.

Các đầu vào runtime được hỗ trợ là mảng `int[]`, `ArrayList<Integer>` và
`LinkedList<Integer>`. Danh sách có `null` được chiếu thành dữ liệu không đủ
ở phần tử đó; không gọi `get`, `toString`, hoặc mã người dùng trong VM đích.
`List` có triển khai khác, luồng/stream, iterator, chuỗi, linked-list node,
và chỉ số kiểu `long` nằm ngoài đợt đầu. Detector không đoán vai trò từ tên
biến, tên hàm, chú thích, hoặc một ảnh chụp runtime đơn lẻ.

## Ranh giới kiến trúc

`AlgorithmPatternAnalyzer` giữ nhiệm vụ điều phối và thứ tự ưu tiên hiện có:
sort, Activity Selection, rồi các mẫu mảng/danh sách. `TwoPointersDetector`
riêng phân tích AST và trả về `two-pointers` cùng metadata có cấu trúc.
Các tiện ích vai trò biến, alias và điều kiện chuẩn hóa chỉ được chia sẻ khi
chúng có cùng ngữ nghĩa với detector kế tiếp; không đưa tất cả thuật toán vào
một bộ nhận diện chung mơ hồ. `TwoPointersTraceBuilder` nối hint với quan sát
JDI theo lời gọi, luồng, định danh dữ liệu và ID khai báo biến. Trường nullable
`twoPointers` trong `AlgorithmHint` và `twoPointersFrame` trong `VisualState`
được thêm vào JSON; các trường, constructor và endpoint cũ tiếp tục hoạt động.

Detector dùng khóa khai báo (qua `LocalVariableBindings`) cho con trỏ và dữ
liệu. Alias cục bộ chỉ được theo khi nguồn của nó xác định và không bị gán lại
trong vùng liên quan. Vòng `for`, `while`, `do/while`, so sánh đảo vế, cập nhật
`++`, `+= 1`, `p = p + 1` (và dạng giảm tương ứng), cùng `array[index]` /
`list.get(index)` trên kiểu tĩnh `List<Integer>` hoặc triển khai được hỗ trợ
được chuẩn hóa về vai trò và tác dụng. Helper cùng lớp chỉ
được theo nếu đích gọi xác định, không override, không có tác dụng phụ ngoài
phạm vi chứng minh. Khi có ghi qua alias, gọi hàm không phân giải được, hoặc
đường đi điều khiển không chứng minh được, detector từ chối hint chuyên biệt.

## Điều kiện chứng minh tĩnh

**Hai đầu vào giữa:** xác định hai chỉ số khác nhau, khởi tạo ở biên trái và
phải của cùng một dữ liệu. Tham số chỉ số chỉ được chấp nhận khi chính mã nguồn
kiểm tra tiền điều kiện biên trước lần truy cập đầu tiên.
Điều kiện lặp bảo đảm khoảng hai con trỏ còn hợp lệ. Thân vòng đọc, so sánh
hoặc trao đổi hai vị trí; các nhánh tiếp tục phải tăng trái, giảm phải, hoặc
kết thúc. Bỏ qua đường thoát `return`/`break` hợp lệ; không chấp nhận nhánh
tiếp tục mà hai con trỏ đứng yên, đi ngược chiều, vượt nhau rồi vẫn truy cập,
hay đọc hai collection khác nhau. Với `do/while`, cần chứng minh điều kiện
vào vòng bảo vệ dữ liệu rỗng và biên truy cập đầu tiên. Tác dụng đọc/ghi phải gắn với hai vị trí,
không chỉ xuất hiện cạnh nhau trong cùng vòng lặp.

**Cùng chiều:** xác định chỉ số `scan` tiến qua cùng dữ liệu và `write`/`slow`
không vượt trước nó. `slow` giữ lại hoặc lùi sau `scan` qua quyết định có
điều kiện; thân vòng dùng hai vị trí trong một phép so sánh, sao chép hoặc ghi
giữ phần tử. `scan` phải tiến trên mọi đường tiếp tục; `slow` chỉ tiến hoặc
giữ nguyên, không được gán từ chỉ số khác không chứng minh được. Hai chỉ số
cùng tăng vô điều kiện và luôn trùng nhau là vòng quét đơn, không cấp hint.
Quy tắc này nhận ra mẫu lọc/compact và so sánh phần tử kế cận, nhưng không
suy ra đầu ra đã được lọc hay đã khử trùng lặp đúng.

Mọi bằng chứng gồm loại chuyển động, nguồn dữ liệu, quan hệ biên, đường đi
điều khiển và vị trí AST cụ thể. Nếu chỉ thấy hai biến hoặc hai phép đọc nhưng
không chứng minh được tác dụng, vẫn giữ quan sát tổng quát. `confidence` không
được dùng để nâng một bằng chứng thiếu thành kết luận chắc chắn. Ưu tiên
`sliding-window` khi accumulator, thêm phần tử vào và bỏ phần tử ra tạo ra
cửa sổ có nghĩa; ưu tiên sort khi phép đổi chỗ thuộc một mẫu sort đã chứng
minh. Mỗi vòng lặp chỉ có một hint như API hiện tại.

## Runtime và hiển thị

Metadata tĩnh nêu kiểu chuyển động (`OPPOSING` hoặc `SAME_DIRECTION`), ID
khai báo của dữ liệu và hai con trỏ, và chứng cứ đã chứng minh. Frame JDI nêu
giá trị con trỏ, kích thước dữ liệu, vị trí đang đọc/ghi, chênh lệch so với
ảnh chụp trước và trạng thái `PENDING`, `OBSERVED`, `UNKNOWN`, `TRUNCATED` hoặc
`VIOLATED`. Ảnh chụp ở trước lệnh ghi chỉ tạo `PENDING`; chỉ ảnh chụp sau lệnh
trên cùng lời gọi và cùng object mới được xác nhận. Ảnh chụp bị bỏ lỡ không
được nội suy thành chuỗi bước đã xảy ra. Nếu đổi object, thiếu biến, phần tử
`null`, danh sách bị cắt, hoặc chỉ số ngoài biên, frame ghi rõ lý do và không
gắn nhãn đã xác minh. Một chuyển động quan sát được trái điều kiện là
`VIOLATED` cho lần chạy đó, không có nghĩa mọi lần chạy đều sai.

UI hiện con trỏ, vùng giữa chúng hoặc đoạn đã quét, phần tử tại vị trí quan
sát được, cùng nhãn riêng cho bằng chứng nguồn và runtime. Giữ render biến
tổng quát khi không có frame; không hiển thị lời khẳng định về độ đúng của
pair-sum, palindrome, compact hay độ phức tạp. Dữ liệu nguồn được escape như
giao diện hiện tại.

## Kiểm thử chấp nhận

- Corpus dương cho cả hai kiểu di chuyển và hai kiểu dữ liệu: đổi tên,
  `for`/`while`/`do/while`, đảo so sánh, ba dạng cập nhật, alias bất biến,
  `get`/`set` khi dùng `List`, kết hợp nhiều biến thể trong một fixture.
- Corpus âm gần kề: hai collection khác nhau, con trỏ đổi chiều, nhánh không
  tiến, alias bị gán lại, chỉ số trùng luôn luôn, phép đọc không liên quan,
  lời gọi có side effect hoặc dispatch không rõ, vòng sort/sliding window
  bị gắn nhầm `two-pointers`. Không có ca nào được cấp hint sai.
- Runtime: rỗng, một phần tử, chỉ số sát biên, trùng giá trị, số âm, nhiều
  lần gọi cùng nội dung nhưng object khác, nhiều luồng/lời gọi, ảnh chụp
  thiếu, danh sách dài bị cắt, `null` trong list. Kiểm tra không nối nhầm
  frame và không xác nhận bước trước khi quan sát sau tác dụng.
- API và UI: metadata/frame là trường cộng thêm, `/api/debug` cũ không đổi;
  khi thiếu dữ liệu vẫn thấy biến tổng quát. Chạy test tập trung, toàn
  backend, kiểm tra cú pháp UI và diff; ghi đúng giới hạn formatter/JDK nếu
  môi trường hiện tại không chạy được.

## Thứ tự sau khi duyệt spec

1. Corpus AST dương/âm và normalizer vai trò, quan hệ biên, chuyển động.
2. Detector cho hai đầu vào giữa, rồi cùng chiều; giữ thứ tự ưu tiên hint.
3. Chiếu `List<Integer>` có giới hạn và trace JDI theo định danh lời gọi/dữ liệu.
4. API, UI, mẫu nguồn, tài liệu và hồi quy toàn bộ.
