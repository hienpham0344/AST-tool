# Heap Sort Semantic Recognition Design

## Mục tiêu

Thêm nhận diện và trực quan hóa Heap Sort trên `int[]` mà không buộc người dùng
viết đúng một mẫu `for`, tên hàm, thứ tự tham số helper, hay công thức chỉ số
dưới cùng một dạng ký tự. Điều kiện nhận diện là **tác dụng đã chứng minh được**:
dựng một heap trên mảng, liên tiếp chuyển phần tử ở gốc tới vùng kết quả,
thu nhỏ heap và khôi phục tính chất heap. AST cấp nhãn thuật toán; ảnh chụp JDI
chỉ cấp giá trị và xác minh trạng thái chạy, không tạo nhãn từ một đầu ra tình
cờ đã sắp xếp.

Không thể quyết định tính tương đương của mọi chương trình Java bất kỳ. Khi
một tác dụng cần thiết không chứng minh được trong phạm vi hỗ trợ, hệ thống
không cấp `heap-sort` và vẫn trả về quan sát runtime tổng quát. Không báo mã
người dùng sai chỉ vì không khớp mẫu.

## Phạm vi

- Mảng `int[]` đánh chỉ số từ 0; sắp xếp toàn bộ mảng tại chỗ.
- Max-heap tạo kết quả tăng dần; min-heap tạo kết quả giảm dần. Giá trị trùng
  nhau được phép; thuật toán không cần ổn định.
- Hai cách dựng heap: từ dưới lên bằng `siftDown` cho các nút trong, hoặc thêm
  từng phần tử bằng `siftUp`. Hai cách này cùng đi tới bất biến heap, không
  cần có cùng hình dạng AST.
- `siftDown` dạng lặp hoặc đệ quy. Chấp nhận mã viết trực tiếp hoặc helper
  trong cùng lớp khi mọi tác dụng liên quan có thể kiểm tra và lời gọi không
  thể bị Java dispatch chuyển sang triển khai khác.
- Dạng vòng `for`/`while`, tên biến và hàm bất kỳ, tăng/giảm bộ đếm tương
  đương, so sánh đảo hai vế, heap size dạng độ dài loại trừ hoặc last index
  dạng bao gồm. Một alias mảng cục bộ chỉ được chấp nhận khi có đúng một phép
  gán từ tham số mảng và không bị gán lại.

Ngoài phạm vi: `PriorityQueue`, heap d-ary, chỉ sắp một subrange, mảng đối
tượng/comparator, heap lưu trong mảng khác rồi chép về, helper ngoài lớp hoặc
helper có dispatch động, alias qua field hay lời gọi không rõ tác dụng.

## Bất biến nhận diện

1. **Cùng mảng:** dựng heap, đổi chỗ và rút phần tử tác động lên cùng một
   mảng `int[]`, đã nối về đúng khai báo; không có phép ghi khác vào mảng đó
   trong vùng thuật toán được nhận diện.
2. **Heap:** tại điểm kết thúc dựng heap, vùng `[0, heapSize)` là max-heap
   hoặc min-heap. Với mỗi nút cha, thuật toán xét các con còn nằm trong vùng
   này, chọn con ưu tiên đúng chiều, rồi đổi chỗ/di chuyển chỉ số đến khi
   quan hệ cha-con được khôi phục. `siftUp` phải so với cha và tiến về gốc.
3. **Rút phần tử:** mỗi lượt đổi `array[0]` với phần tử cuối của heap cũ;
   heap giảm đúng một phần tử; `siftDown` chỉ chạy trong heap mới. Vùng hậu tố
   đã rút không bị ghi lại. Lượt lặp kết thúc khi heap còn tối đa một phần tử.
4. **Thứ tự:** max-heap tương ứng kết quả tăng dần, min-heap tương ứng kết quả
   giảm dần. So sánh đảo toán hạng phải giữ cùng ý nghĩa quyết định.
5. **Tiến triển và hiệu ứng:** mọi đường đi được chấp nhận đều giảm vùng heap
   hoặc tiến chỉ số lên/xuống cây; mọi swap có đủ tác dụng trên đúng hai ô.
   Không suy luận từ tên `heapify`, `sort`, `left`, `right` hay một lần chạy mẫu.

Các bất biến trên được kiểm tra trong họ mã xác định ở phần phạm vi; chúng
không phải tuyên bố chứng minh hình thức cho Java tổng quát.

## Phân tích tĩnh

Giữ registry detector hiện tại, thêm `HeapSortDetector`. Detector dùng một
biểu diễn sự kiện nội bộ nhỏ cho các vai trò `array`, `heapBoundary`, `node`,
`leftChild`, `rightChild`, `chosenChild` và các thao tác `COMPARE`, `SWAP`,
`MOVE_INDEX`, `SHRINK_HEAP`, `CALL_SIFT`. Vai trò được nối với khai báo AST;
tên và vị trí tham số helper không phải điều kiện nhận diện.

Chuẩn hóa có giới hạn các biểu thức chỉ số và biên: `2*i+1`, `i*2+1`,
`(i << 1)+1` cùng chỉ con trái; con phải là `2*i+2` hoặc `left+1` khi `left`
đã được chứng minh. Các dạng `heapSize` và `lastIndex` được quy về ranh giới
loại trừ `[0, heapSize)`. Chỉ chuẩn hóa khi toán hạng không có tác dụng phụ
và biến chưa bị gán lại giữa lần định nghĩa và lần dùng. Vòng `for`/`while`
được đối chiếu qua giá trị đầu, điều kiện, bước tiến và thân; vị trí mã nguồn
gốc vẫn được giữ trong `operationAstNodeIds`.

Phân tích helper giới hạn trong cùng compilation unit và lớp đích. Theo dõi
đối số theo vai trò, kiểm tra các đường ghi mảng và đích lời gọi. Với phương
thức instance có thể override, tên lớp bị shadow bởi local/field hoặc đích
không rõ, từ chối nhận diện thay vì đoán. Không cần thêm thư viện hay sửa API
cũ để làm bước chuẩn hóa này.

Detector phát ra `AlgorithmHint.type = "heap-sort"` và metadata `heap` mới
theo cách cộng thêm, gồm chiều sắp xếp, kiểu dựng heap, các ID khai báo vai
trò, ID thao tác dựng/rút/sift/swap, ID phương thức helper đã chứng minh.
Các trường `sort`, `quick`, `merge` và phản hồi cũ vẫn giữ nguyên ý nghĩa.

## Runtime và giao diện

`HeapTraceBuilder` dùng `operationAstNodeIds`, thread/invocation ID và
`arrayObjectId` để gắn ảnh chụp JDI đúng lượt gọi. `HeapFrame` nêu pha
`BUILD_HEAP`, `EXTRACT_ROOT`, `SIFT_DOWN`, `SIFT_UP` hoặc `COMPLETE`; ranh giới
heap, nút hiện tại, các con hợp lệ, nút được chọn, phép so sánh, swap đang chờ
và swap đã được ảnh chụp sau xác nhận. Nếu helper chạy ở frame khác, trace
phải nối đúng lời gọi cha-con trước khi hiển thị tác dụng của helper.

Ảnh chụp tại breakpoint biểu diễn trạng thái trước dòng đang tô sáng. Một
phép ghi chỉ là `PENDING_BEFORE_LOCATION` tại dòng đó; chỉ thành
`OBSERVED_AFTER_WRITE` khi ảnh chụp sau trên cùng mảng và đúng lời gọi xác
nhận hai ô liên quan. `HEAP_VERIFIED` chỉ dùng tại điểm dựng/khôi phục heap
đã hoàn tất và ảnh chụp toàn vùng thỏa quan hệ cha-con. `SORTED_SUFFIX_VERIFIED`
chỉ dùng khi hậu tố đầy đủ có thứ tự đúng; `VERIFIED_SORTED` cho toàn mảng
chỉ xuất hiện tại điểm quan sát sau lời gọi sort và khi toàn mảng được chụp
đầy đủ, có thứ tự đúng. Thiếu, lỗi hoặc cắt ngắn dữ liệu giữ trạng thái
`UNKNOWN`/`TRUNCATED`; lỗi thứ tự quan sát được là `NOT_SORTED`.

Giao diện hiển thị mảng với ranh giới heap và vùng hậu tố, nút cha/con,
quyết định so sánh, swap pending/completed và trạng thái xác minh. Không tô
vùng hậu tố là đã xong chỉ vì AST dự kiến rút phần tử. Khi không có heap hint,
giao diện runtime tổng quát vẫn hoạt động.

## Kiểm thử và tiêu chí chấp nhận

- Corpus dương có ít nhất: hai chiều sắp xếp; dựng từ dưới lên và thêm từng
  phần tử; `siftDown` lặp/đệ quy; inline/helper; `for`/`while`; đổi tên toàn
  bộ vai trò; đảo hai vế so sánh; các công thức con và biểu diễn ranh giới
  tương đương. Ít nhất một fixture kết hợp nhiều biến thể để tránh hỗ trợ
  riêng lẻ nhưng hỏng khi dùng cùng nhau.
- Mỗi nhóm dương có ca âm gần kề: sai chỉ số con, không xét con phải, chọn
  con sai chiều, swap thiếu một ghi, không giảm heap, ghi vào hậu tố, ghi vào
  mảng khác, helper có tác dụng không rõ hoặc có thể override, alias bị gán
  lại. Không ca âm nào nhận `heap-sort`.
- Runtime chạy ít nhất các mảng rỗng, một phần tử, đã tăng, đã giảm, trùng,
  số âm, độ dài chẵn/lẻ và nhiều lời gọi trên cùng mảng. So sánh từng quyết
  định và swap hiển thị với giá trị JDI; kiểm tra heap property ở điểm hoàn
  tất mỗi pha, sorted suffix và kết quả cuối. Thử ảnh chụp thiếu/cắt ngắn
  để chắc chắn không xác minh sai.
- Kiểm tra JSON cộng thêm không phá payload cũ; toàn bộ test backend đạt;
  UI script hợp lệ và luồng hiển thị được kiểm tra khi có bề mặt trình duyệt
  được phép truy cập. Ghi rõ kết quả kiểm thử thực tế, không suy đoán.

## Thứ tự triển khai sau khi spec được duyệt

1. Corpus AST và các ca âm cho bất biến heap, ranh giới và hiệu ứng swap.
2. Chuẩn hóa vai trò/biểu thức và detector, bắt đầu bằng dựng từ dưới lên,
   sau đó thêm cách dựng bằng `siftUp` cùng metadata ổn định.
3. Trace JDI cho pha dựng/rút/sift và xác minh vùng; bài thử runtime trước UI.
4. UI, tài liệu, kiểm thử hồi quy năm sort đang có và review độc lập.
