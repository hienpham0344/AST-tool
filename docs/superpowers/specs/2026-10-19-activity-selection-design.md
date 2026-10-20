# Activity Selection Semantic Recognition Design

## Mục tiêu

Nhận diện và trực quan hóa Activity Selection (Interval Scheduling) trong mã
Java 17 khi tên biến, kiểu vòng lặp hoặc cách biểu diễn khoảng khác nhau. Đợt
đầu hỗ trợ cả `int[][]` và `List<Interval>`. Detector chứng minh quy tắc chọn
theo thời điểm kết thúc và điều kiện không chồng lấn trong một họ chương trình
xác định; ảnh chụp JDI cung cấp giá trị và xác nhận tác dụng của từng lần chạy.
Không suy ra thuật toán chỉ từ tên `greedy`, tên phương thức, hoặc một đầu ra
tình cờ hợp lệ.

Greedy là một chiến lược gồm nhiều bài toán với bất biến khác nhau. Spec này
chỉ bao gồm Activity Selection. Khi không chứng minh được tác dụng cần thiết,
API vẫn trả quan sát runtime tổng quát; không kết luận mã người dùng sai.

## Phạm vi và quy ước

- Mỗi khoảng có `start` và `end` kiểu `int`, với `start <= end`. Dữ liệu có
  thể là hàng `int[]` trong `int[][]`, hoặc phần tử của `List<Interval>`.
- Với `int[][]`, detector tìm hai chỉ số cột hằng khác nhau qua các phép đọc
  trong thuật toán; không gắn ý nghĩa cho tên biến hoặc mặc định cột 0/1.
  Hàng phải có đủ hai cột tại runtime để xác minh quyết định.
- Với `List<Interval>`, `Interval` là `record` hoặc lớp cùng nguồn có hai
  trường `int` bất biến được đọc trực tiếp hoặc qua accessor chỉ trả lại một
  trường. Detector nối accessor về khai báo trường. `ArrayList` và
  `LinkedList` là hai triển khai runtime được hỗ trợ đợt đầu.
- Vòng quét có thể là `for`, `while`, hoặc enhanced `for`. Kết quả có thể là
  danh sách các khoảng được chọn hoặc số lượng được chọn. Cho phép tên biến
  bất kỳ, bản sao cục bộ bất biến, so sánh đảo hai vế, và helper cùng lớp có
  đích gọi xác định, không thể bị override.
- Hai chính sách giao nhau được tách rõ: `start >= lastEnd` cho khoảng nửa
  mở `[start,end)`, hoặc `start > lastEnd` cho khoảng đóng `[start,end]`.
  Không đổi một chính sách sang chính sách kia khi chuẩn hóa AST.
- Trường hợp không có khoảng và một khoảng phải kết thúc đúng; giá trị thời
  gian âm, bằng nhau và trùng khoảng được xét. Khởi tạo `lastEnd` phải an
  toàn với miền `int`, thí dụ `Integer.MIN_VALUE` cho chính sách nửa mở hoặc
  nhánh chọn khoảng đầu tiên; không mặc định `0` là âm vô cùng.

Ngoài phạm vi đợt đầu: khoảng có trọng số, yêu cầu chọn đủ số lượng, nhiều
tài nguyên/phòng, thời điểm kiểu `long` hoặc ngày giờ, dữ liệu phát sinh qua
stream, comparator/helper ngoài lớp không kiểm tra được, đối tượng có trường
thời gian bị sửa trong lúc chạy, và thuật toán sắp thứ tự bằng một chương
trình con tùy ý. Các bài toán Greedy khác có detector và spec riêng.

## Bằng chứng tĩnh và trạng thái thứ tự

Detector chỉ phát `activity-selection` sau khi chứng minh các điều sau:

1. **Cùng tập khoảng:** vòng quét, phép đọc hai mốc và tác dụng chọn đều nối
   tới cùng khai báo tập dữ liệu; alias không được gán lại. Với `List`, kiểu
   phần tử và hai accessor/trường phải nối về cùng kiểu `Interval`.
2. **Quét đầy đủ:** chỉ số hoặc iterator tiến đúng một phần tử mỗi lượt cho
   tới hết tập. Không có đường `break`, `return`, thao tác thêm/xóa tập đầu
   vào, hoặc helper không rõ tác dụng làm bỏ qua ứng viên.
3. **Quyết định:** mỗi ứng viên được chọn khi và chỉ khi `start` thỏa chính
   sách giao nhau với `lastEnd`. Nhánh không chọn không đổi `lastEnd` hay đầu
   ra. Nhánh chọn cập nhật `lastEnd` thành `end` của chính ứng viên và tăng
   số lượng hoặc thêm đúng ứng viên vào danh sách kết quả. Chấp nhận một
   nhánh riêng cho ứng viên đầu tiên khi nó tương đương quy tắc này.
4. **Không phá dữ liệu:** trong vùng được nhận diện không có ghi vào mốc của
   khoảng, đổi dữ liệu đầu vào sau bước sắp, hoặc hiệu ứng khác làm điều
   kiện đã chứng minh không còn đúng.

Thứ tự tăng theo `end` là một bằng chứng **riêng**. Đợt đầu chứng minh thứ tự
tĩnh cho lời gọi `Arrays.sort` trên `int[][]` hoặc `List.sort`/`Collections.sort`
trên đúng tập `List<Interval>`, khi comparator là một phép so sánh tăng theo
`end` không có tác dụng phụ: `Comparator.comparingInt`, `Integer.compare`,
hoặc lambda tương đương trong cùng nguồn. Chấp nhận đảo toán hạng chỉ khi
chiều so sánh vẫn là tăng theo `end`. Lời gọi sort phải chạy trước vòng quét,
trên toàn tập, không có ghi làm đổi thứ tự hay mốc thời gian giữa hai bước;
helper chỉ được chấp nhận khi cùng lớp, không thể override và có thể kiểm tra
toàn bộ tác dụng. Với dữ liệu đã sắp từ trước nhưng không có bằng chứng sắp
trong vùng nguồn được phân tích, metadata ghi
`orderingEvidence = INPUT_PRECONDITION`, không ghi `SOURCE_PROVEN`.

`AlgorithmHint.type = "activity-selection"` mô tả quy tắc chọn đã chứng minh;
nó không tự khẳng định tính tối ưu. Metadata `activitySelection` phiên bản 1
gồm kiểu biểu diễn, chính sách giao nhau, trạng thái bằng chứng thứ tự,
declaration IDs cho tập/ứng viên/mốc cuối, field hoặc column roles, operation
AST IDs cho sort, vòng quét, điều kiện, cập nhật và tác dụng đầu ra. Giữ nguyên
các constructor và trường JSON đang có của `AlgorithmHint`.

## Ranh giới thành phần

`ActivitySelectionDetector` nhận loop và AST chứa nó, trả một hint khi các
bằng chứng trên hoàn tất. Nó tích hợp vào `AlgorithmPatternAnalyzer` sau các
detector sort hiện có và trước các heuristic tìm kiếm. Không đưa Activity
Selection vào `SortPatternRegistry`; registry đó chỉ quản lý sort. Một
normalizer nhỏ chịu trách nhiệm ánh xạ hàng mảng hoặc trường/accessor về
`start`/`end`; detector chịu trách nhiệm luồng điều khiển và tác dụng.

`/api/debug/steps` tính hints trước khi chạy JDI để truyền một mô tả chiếu
giá trị đã chứng minh cho lớp quan sát. `JdiSession.observe(target, analysis)`
và `/api/debug` cũ vẫn hoạt động qua overload mặc định không có chiếu riêng.
`VariableMapper` chỉ đọc hai trường `int` đã chứng minh của đúng kiểu
`Interval` trong hint, không gọi getter hoặc `toString()` trong VM đích.
Chiếu giá trị bị giới hạn bởi ngân sách phần tử và độ sâu hiện có; thiếu,
khác kiểu hoặc bị cắt ngắn trả `UNKNOWN`/`TRUNCATED`. `int[][]` tiếp tục dùng
ảnh chụp mảng lồng nhau hiện có.

`ObservationResult` thêm ID đối tượng nội bộ, bỏ qua khi serialize JSON và
giữ các constructor cũ. Array ID hiện tại giữ nguyên cho sort; ID mới cho
phép nối `List` đúng đối tượng qua các ảnh chụp. `ActivitySelectionTraceBuilder`
chỉ gắn bước cùng thread, invocation và tập dữ liệu. Nó không dùng giá trị
của lời gọi khác để xác nhận quyết định đang chờ.

## Runtime, API và giao diện

`ActivitySelectionFrame` nêu ứng viên, `start`, `end`, `lastEnd`, chính sách
giao nhau, quyết định được tính từ ảnh chụp, thao tác chọn đang chờ, thao tác
đã quan sát, số lượng/tập đã chọn, trạng thái thứ tự và trạng thái không
chồng lấn. Ảnh chụp breakpoint là `BEFORE_LOCATION`; việc thêm ứng viên hoặc
cập nhật mốc chỉ được ghi `OBSERVED_AFTER_WRITE` sau ảnh chụp sau đó trên
đúng tập và invocation. Giá trị thiếu không được suy ra từ AST.

Trace kiểm tra dãy `end` đã **thực sự đi qua** có không giảm hay không và
kiểm tra từng `start <= end`, các khoảng đã chọn không chồng lấn theo chính
sách tương ứng, và kết quả đã quan sát khớp các lần chọn được xác nhận.
`SOURCE_PROVEN` là bằng chứng từ mã; `RUNTIME_VERIFIED` chỉ đúng cho lần
chạy có đủ quan sát. Thiếu một ứng viên, ảnh chụp cắt ngắn, hoặc không đọc
được trường giữ trạng thái `UNKNOWN`/`TRUNCATED`; một vi phạm quan sát được
là `VIOLATED`. UI chỉ nói “đã xác minh trong lần chạy này” khi runtime đủ
bằng chứng; chỉ nói “tối ưu theo quy tắc Activity Selection” khi thứ tự theo
`end` đã được chứng minh tĩnh hoặc được xác minh đầy đủ cho lần chạy, và
mọi quyết định chọn cùng đầu ra đã được xác nhận.

Thêm nullable `activitySelection` vào `AlgorithmHint`, nullable
`activitySelectionFrame` vào `VisualState`, giữ trường cũ và payload
`/api/debug` tương thích. UI hiển thị thứ tự ứng viên, mốc kết thúc hiện tại,
nhận/bỏ qua, tập đã chọn, trạng thái nguồn và runtime bằng nhãn khác nhau.
Khi không có hint hoặc không đủ quan sát, giao diện biến runtime tổng quát
vẫn hoạt động.

## Kiểm thử chấp nhận

- Corpus dương cho cả hai biểu diễn: `for`/`while`/enhanced `for`, record/lớp
  trường `final`, direct field/accessor, đổi tên, đảo so sánh, hai chính sách
  giao nhau, kết quả số lượng/danh sách, sort tường minh và đầu vào đã sắp.
  Ít nhất một fixture kết hợp nhiều biến thể.
- Corpus âm gần kề: sắp theo `start` hoặc `end` giảm, comparator có tác dụng
  phụ, điều kiện chọn sai, cập nhật `lastEnd` bằng `start` hoặc ứng viên khác,
  bỏ qua ứng viên, ghi dữ liệu đầu vào, alias gán lại, đối tượng mutable,
  helper có dispatch không rõ. Không ca nào được ghi `SOURCE_PROVEN` sai;
  ca phá quy tắc chọn không được cấp hint.
- JDI chạy mảng/list rỗng, một phần tử, khoảng chồng lấn, chạm biên, trùng,
  giá trị âm, nhiều lời gọi và hai tập có nội dung bằng nhau. Kiểm tra
  `PENDING` rồi `OBSERVED_AFTER_WRITE` đúng object/invocation, trạng thái
  `UNKNOWN`/`TRUNCATED` khi thiếu dữ liệu, và đối chiếu đầu ra với oracle
  tối ưu độc lập trên bộ dữ liệu nhỏ.
- API kiểm tra metadata/frames cộng thêm và payload cũ. Chạy kiểm thử tập
  trung, toàn backend, formatter cho các tệp đổi, `git diff --check`, kiểm
  tra cú pháp script UI; thử giao diện thực tế khi có bề mặt trình duyệt
  được phép dùng. Ghi đúng kết quả đã quan sát.

## Thứ tự triển khai sau khi spec được duyệt

1. Corpus AST và normalizer cho hai kiểu khoảng; chốt các ca dương/âm.
2. Detector chứng minh quy tắc chọn và bằng chứng sắp theo `end`.
3. Chiếu JDI có giới hạn cho `List<Interval>`, ID đối tượng và trace của
   quyết định/chọn; kiểm thử runtime trước UI.
4. API, giao diện, mẫu nguồn, tài liệu, hồi quy và rà soát độc lập.
