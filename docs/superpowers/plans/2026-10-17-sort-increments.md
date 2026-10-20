# Semantic Sort Recognition Implementation Plan — Reviewable Increments

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan one increment at a time. Checkboxes track work; stop for user review after each increment.

**Goal:** Nhận diện và trực quan hóa đúng Bubble, Selection, Insertion qua các cách viết tương đương đã công bố; chỉ sau đó mở rộng sang Quick và Merge.

**Architecture:** JavaParser AST và liên kết khai báo biến tạo mô hình nội bộ về vòng lặp, điều kiện, truy cập mảng và phép ghi. Detector của từng thuật toán khớp trên mô hình này. JDI cung cấp giá trị chạy thật và xác nhận các thao tác trước khi UI báo hoàn tất. Quick/Merge thêm metadata và frame riêng vì mô hình swap ba lệnh hiện tại không biểu diễn đủ đệ quy, partition và mảng phụ.

**Tech Stack:** Java 17, Spring Boot, JavaParser, JDI, JUnit 5, Maven, HTML/JavaScript hiện có.

**Spec:** [Semantic sort roadmap](2026-10-17-sort-quality-roadmap.md) và [sort visualization guide](../../sort-visualization-test-guide.md).

## Cách sử dụng kế hoạch

“Sprint” bên dưới nghĩa là **một phần việc có thể triển khai, chạy kiểm thử và duyệt riêng**, không gắn với số ngày. Chỉ làm sprint kế tiếp sau khi kết quả của sprint trước đã được người dùng duyệt. Nếu một ví dụ tương đương mới xuất hiện, thêm nó vào bộ mẫu của Sprint 0 rồi xác định sprint sở hữu trước khi sửa detector.

Mỗi sprint bàn giao: thay đổi mã/tài liệu, tên các kiểm thử đã chạy và kết quả, ví dụ đầu vào/đầu ra liên quan, `git diff --check`, và các giới hạn còn lại. Các lệnh Maven chạy từ `backend/`. Môi trường dùng để thực hiện cần JDK 17+ và Maven 3.9+; tại thời điểm lập kế hoạch, lệnh `mvn` chưa có trong môi trường này.

## Ràng buộc chung

- Giữ nguyên phản hồi hiện có của `/api/debug`; mọi trường mới ở `/api/debug/steps` phải cộng thêm và tương thích với dữ liệu của ba sort cũ.
- Không dùng tên biến hoặc một lần chạy có kết quả đã sắp xếp làm bằng chứng duy nhất để gắn nhãn thuật toán.
- Khi không chứng minh được liên kết biến, alias mảng hoặc tác dụng của hàm phụ, không gắn nhãn sort chuyên biệt; vẫn trả về quan sát runtime chung.
- Mỗi thao tác “đã hoàn tất” cần snapshot sau phép ghi. Mảng thiếu dữ liệu hoặc bị cắt không được báo `VERIFIED_SORTED`.
- Kiểm thử dương và âm phải đi trước thay đổi detector; chạy kiểm thử tập trung sau mỗi sprint và toàn bộ backend ở các cổng chất lượng.

## Phần A — Hoàn thiện ba sort nền tảng

### Sprint 0 — Chốt bộ mẫu và trạng thái hiện tại

**Tệp:** Tạo mẫu trong `backend/src/test/resources/sort-patterns/`; tạo `backend/src/test/java/com/example/astchunker/algorithm/SortPatternCorpusTest.java`; cập nhật `docs/sort-visualization-test-guide.md`.

- [ ] Lập bảng mẫu dương/âm cho Bubble, Selection, Insertion: đổi tên; `for`/`while`; cách tăng chỉ số; biên tương đương; chiều so sánh; Bubble early-exit; inline swap; helper swap đơn giản cùng file; trường hợp gần đúng nhưng sai.
- [ ] Thêm kiểm thử dữ liệu hóa: mỗi mẫu dương phải cho đúng `type`, `direction`, AST ID của phép so sánh/ghi; mỗi mẫu âm phải không cho sort hint. Đánh dấu các mẫu chưa hỗ trợ là kiểm thử chờ, không làm suite hiện tại đỏ vô cớ.
- [ ] Chạy `mvn -Dtest=BubbleSortDetectorTest,SelectionSortDetectorTest,InsertionSortDetectorTest,SortPatternCorpusTest test` để ghi baseline và các khoảng trống.
- [ ] Duyệt bảng mẫu trước Sprint 1. **Đầu ra:** danh sách cụ thể những cách viết hệ thống cam kết nhận diện và những trường hợp phải từ chối.

### Sprint 1 — Chuẩn hóa vòng lặp và biểu thức chỉ số

**Tệp:** Tạo `backend/src/main/java/com/example/astchunker/algorithm/sort/CountedLoop.java` và `IndexExpression.java`; tạo `CountedLoopTest.java`, `IndexExpressionTest.java` trong `backend/src/test/java/com/example/astchunker/algorithm/`.

- [ ] Viết kiểm thử đỏ: `for` và `while` có cùng khởi tạo/điều kiện/bước tăng cho cùng mô hình; `j < n - 1 - pass` và `j + 1 < n - pass` có cùng biên hiệu dụng; `j++`, `++j`, `j += 1`, `j = j + 1` có cùng bước tiến.
- [ ] Kiểm thử từ chối: tăng có điều kiện, thiếu tăng, ghi lại counter trong thân, biểu thức biên phi tuyến hoặc tham chiếu không giải được.
- [ ] Triển khai mô hình nội bộ có AST ID của vòng lặp/counter và biểu thức chỉ số chuẩn hóa; không đổi DTO/API.
- [ ] Chạy `mvn -Dtest=CountedLoopTest,IndexExpressionTest test`; duyệt mô hình và các giới hạn trước Sprint 2.

### Sprint 2 — Phân tích tác động lên mảng và hàm swap đơn giản

**Tệp:** Tạo `backend/src/main/java/com/example/astchunker/algorithm/sort/ArrayEffects.java` và `ArrayEffectsTest.java`; mở rộng `LocalVariableBindings` cùng kiểm thử của nó nếu cần theo dõi alias trong một method.

- [ ] Viết kiểm thử đỏ cho đọc `a[i]`, ghi `a[j]`, hoán đổi ba lệnh, dịch phần tử của Insertion, và helper `swap(int[] a, int i, int j)` cùng file với tác dụng rõ ràng.
- [ ] Kiểm thử âm cho ghi thiếu, ghi thêm, helper có tác dụng phụ, alias không rõ, hai mảng khác nhau và hàm ngoài file.
- [ ] Triển khai tóm tắt tác động bảo toàn AST ID của nguồn phép ghi; chỉ mở rộng qua lời gọi helper khi đầu vào và tác dụng đều giải được.
- [ ] Chạy `mvn -Dtest=ArrayEffectsTest,LocalVariableBindingsTest test`; duyệt việc helper nào được hỗ trợ trước Sprint 3.

### Sprint 3 — Nhận diện Bubble Sort theo ngữ nghĩa

**Tệp:** Sửa `BubbleSortDetector.java`, `SortAstSupport.java`, `BubbleSortDetectorTest.java`, `SortPatternCorpusTest.java`. [Kế hoạch Bubble `while`](2026-10-17-bubble-sort-while-recognition.md) cung cấp trường hợp đầu tiên.

- [ ] Bật kiểm thử dương Bubble từ corpus: `for`/`while`, biên tương đương, chiều tăng/giảm, đổi thứ tự toán hạng, early-exit, đổi tên, inline swap và helper swap đã được Sprint 2 xác nhận.
- [ ] Bật kiểm thử âm: so sánh không kề, swap chưa đủ, sai biên, sai mảng, counter không tiến, alias mơ hồ. Xác nhận chúng thất bại đúng nguyên nhân trước khi sửa.
- [ ] Dùng `CountedLoop`, `IndexExpression`, `ArrayEffects` để suy ra vai trò `array/pass/scan/temp` và thao tác thực; giữ metadata v1 cho dạng cũ.
- [ ] Chạy `mvn -Dtest=BubbleSortDetectorTest,SortPatternCorpusTest test`. Duyệt độ nhận diện và độ từ chối; chưa chuyển sang Selection nếu còn false positive đã biết.

### Sprint 4 — Trace Bubble với các cách viết mới

**Tệp:** Sửa `BubbleSortTraceStrategy.java`, `VisualTraceBuilder.java` khi cần; thêm kiểm thử vào `VisualTraceIntegrationTest.java` và `SortRuntimeIntegrationTest.java`.

- [ ] Viết kiểm thử đỏ: mỗi biến thể Bubble được nhận diện cho comparison, pending write, confirmed swap và vùng đã sort đúng trên mảng `{2,1}`; helper swap phải liên kết đúng bước ở caller/callee.
- [ ] Chạy bộ 6 mảng chuẩn cho `for`, `while`, early-exit; thêm chiều giảm và nhiều lần gọi `sort()` trong một chương trình.
- [ ] Sửa trace dựa trên operation AST ID và context của lần gọi; không giả định ba phép ghi luôn cùng một method. Không đánh dấu swap hoàn tất trước snapshot xác nhận.
- [ ] Chạy `mvn -Dtest=VisualTraceIntegrationTest,SortRuntimeIntegrationTest test`; duyệt trace và ảnh/chụp UI mẫu trước Sprint 5.

### Sprint 5 — Nhận diện Selection Sort theo ngữ nghĩa

**Tệp:** Sửa `SelectionSortDetector.java`, `SelectionSortDetectorTest.java`, `SortPatternCorpusTest.java`.

- [ ] Bật kiểm thử dương: `for`/`while`, chọn min/max, so sánh đảo toán hạng, thay đổi cách tăng/biên, đổi tên, inline/helper swap đã chứng minh tác động.
- [ ] Bật kiểm thử âm: selected index cập nhật sai, scan không đi hết phần còn lại, swap nhầm vị trí, ghi thêm và alias mơ hồ.
- [ ] Triển khai detector trên mô hình chung; giữ các role và metadata cũ cho mẫu hiện hành.
- [ ] Chạy `mvn -Dtest=SelectionSortDetectorTest,SortPatternCorpusTest test`; duyệt danh sách mẫu nhận/từ chối trước Sprint 6.

### Sprint 6 — Trace Selection qua các biến thể

**Tệp:** Sửa `SelectionSortTraceStrategy.java` và, nếu cần, `VisualTraceBuilder.java`; thêm kiểm thử `VisualTraceIntegrationTest.java`, `SortRuntimeIntegrationTest.java`.

- [ ] Kiểm thử selected-index update, comparison, pending/confirmed swap và sorted prefix trên các mẫu mới.
- [ ] Chạy bộ 6 mảng, hai chiều, nhiều lần gọi và trường hợp tự swap; xác nhận nguồn giá trị được đọc từ snapshot đúng lần gọi.
- [ ] Chạy `mvn -Dtest=VisualTraceIntegrationTest,SortRuntimeIntegrationTest test`; duyệt trace trước Sprint 7.

### Sprint 7 — Nhận diện Insertion Sort theo ngữ nghĩa

**Tệp:** Sửa `InsertionSortDetector.java`, `InsertionSortDetectorTest.java`, `SortPatternCorpusTest.java`.

- [ ] Bật kiểm thử dương: pass ngoài bằng `for`/`while`, key và scan đổi tên/chỗ khai báo, so sánh đảo toán hạng, cách giảm scan tương đương, chiều tăng/giảm.
- [ ] Bật kiểm thử âm: guard đọc vượt biên, dịch sai vị trí, quên ghi key, ghi thêm, alias mơ hồ.
- [ ] Dùng mô hình chung để suy ra key, scan, shift và insert; giữ metadata hiện hành cho mẫu cũ.
- [ ] Chạy `mvn -Dtest=InsertionSortDetectorTest,SortPatternCorpusTest test`; duyệt trước Sprint 8.

### Sprint 8 — Trace Insertion qua các biến thể

**Tệp:** Sửa `InsertionSortTraceStrategy.java`; thêm kiểm thử `VisualTraceIntegrationTest.java`, `SortRuntimeIntegrationTest.java`.

- [ ] Kiểm thử comparison, quyết định shift/stop, shift đang chờ/đã xác nhận, ghi key và sorted prefix trên các mẫu mới.
- [ ] Chạy bộ 6 mảng, hai chiều, key trùng, nhiều lần gọi và biên `scan == -1`.
- [ ] Chạy `mvn -Dtest=VisualTraceIntegrationTest,SortRuntimeIntegrationTest test`; duyệt trace trước Sprint 9.

### Sprint 9 — Cổng chất lượng cho Bubble, Selection, Insertion

**Tệp:** Sửa `UI/dashboardPage.html`, `docs/sort-visualization-test-guide.md` và kiểm thử liên quan chỉ theo lỗi thực tế được tìm thấy.

- [ ] Chạy toàn bộ `mvn test`, kiểm thử `/api/debug/steps` và kiểm tra cả corpus dương/âm. Không đóng cổng nếu còn false positive hoặc frame sai đã biết.
- [ ] Trong UI, thử mỗi thuật toán với ít nhất một mẫu cấu trúc cũ và một mẫu mới: highlight, Previous/Next, Play, tốc độ, trạng thái mảng, thông báo không nhận diện và xuất JSON.
- [ ] Ghi rõ những dạng tương đương đã hỗ trợ, dạng chưa hỗ trợ và ý nghĩa của “recognized” so với “runtime verified”.
- [ ] Chạy `git diff --check`; duyệt bộ thay đổi tổng thể. **Chỉ sau khi Sprint 9 được duyệt mới bắt đầu Quick Sort.**

## Phần B — Quick Sort

### Sprint 10 — Nhận diện Quick Sort và metadata đệ quy

**Tệp:** Tạo `QuickSortDetector.java`, `QuickSortDetectorTest.java` trong `algorithm/sort`; thêm metadata riêng trong `model/`; sửa `SortPatternRegistry.java`, `AlgorithmPatternAnalyzer.java`, kiểm thử JSON.

- [ ] Chốt và viết mẫu dương/âm cho một họ partition được chọn, bắt đầu với Lomuto `int[]`: pivot, biên đoạn, vòng scan, swap, lời gọi đệ quy; hai chiều và biến đổi `for`/`while` tương đương trong họ đó.
- [ ] Kiểm thử âm: partition sai biên, pivot không ổn định, thiếu nhánh đệ quy, ghi ngoài đoạn, helper không giải được. Xác nhận detector từ chối.
- [ ] Thêm metadata có AST ID cho pivot/range/compare/swap/recursive call mà không ép vào `SortPattern` v1; giữ JSON của ba sort cũ.
- [ ] Chạy `mvn -Dtest=QuickSortDetectorTest,BubbleSortDetectorTest,SelectionSortDetectorTest,InsertionSortDetectorTest test`; duyệt phạm vi họ partition trước Sprint 11.

### Sprint 11 — Định danh từng lần gọi đệ quy

**Tệp:** Sửa `backend/src/main/java/com/example/astchunker/debug/JdiSession.java`, `model/ExecutionContext.java`; thêm trường hợp vào `ExecutionSemanticsIntegrationTest.java`.

- [ ] Viết kiểm thử đỏ: ID lần gọi ổn định trong một invocation, khác nhau giữa hai lời gọi anh em cùng method/cùng độ sâu, khác nhau giữa các nhánh đệ quy, và không trộn hai thread.
- [ ] Gán ID tăng dần theo từng thread bằng sự kiện vào/ra method của target hoặc cơ chế stack tương đương đã được kiểm thử; thêm `invocationId` vào `ExecutionContext` theo cách cộng thêm, giữ khả năng đọc dữ liệu cũ.
- [ ] Chạy `mvn -Dtest=ExecutionSemanticsIntegrationTest,JdiSessionIntegrationTest test`; duyệt ID và tác động đến debugger trước Sprint 12.

### Sprint 12 — Trace và UI Quick Sort

**Tệp:** Tạo `QuickTraceBuilder.java`, `model/QuickFrame.java`; thêm trường `quickFrame` tùy chọn vào `VisualState`, sửa `VisualTraceBuilder.java`, `UI/dashboardPage.html`; thêm `QuickSortRuntimeIntegrationTest.java`. Giữ `SortTraceBuilder` và `SortFrame` v1 cho ba sort cũ.

- [ ] Kiểm thử pivot và active range đúng `invocationId`, comparison và swap được xác nhận bằng snapshot, partition hoàn tất ở đúng lời gọi.
- [ ] Chạy bộ 6 mảng, hai chiều, nhiều lần gọi; xác nhận thứ tự toàn mảng sau khi thoát lời gọi gốc.
- [ ] Dựng UI cho pivot/range/partition; giữ fallback khi gặp biến thể Quick chưa hỗ trợ.
- [ ] Chạy kiểm thử tập trung, `mvn test`, UI và `git diff --check`; duyệt cổng Quick trước Sprint 13.

## Phần C — Merge Sort

### Sprint 13 — Nhận diện Merge Sort và metadata mảng phụ

**Tệp:** Tạo `MergeSortDetector.java`, `MergeSortDetectorTest.java` trong `algorithm/sort`; thêm metadata riêng trong `model/`; sửa registry/analyzer và kiểm thử JSON.

- [ ] Chốt mẫu dương/âm cho họ top-down merge `int[]`: chia đoạn, đệ quy trái/phải, so sánh hai nửa, ghi vào mảng phụ và copy-back.
- [ ] Kiểm thử âm: đoạn chồng/sót, ghi sai mảng, thiếu copy-back, helper/alias không giải được.
- [ ] Thêm AST ID của range, read/write, auxiliary array và lời gọi đệ quy mà không đổi metadata cũ.
- [ ] Chạy `mvn -Dtest=MergeSortDetectorTest,QuickSortDetectorTest test`; duyệt trước Sprint 14.

### Sprint 14 — Trace và UI Merge Sort

**Tệp:** Tạo `MergeTraceBuilder.java`, `model/MergeFrame.java`; thêm trường `mergeFrame` tùy chọn vào `VisualState`, sửa `VisualTraceBuilder.java`, `UI/dashboardPage.html`; thêm `MergeSortRuntimeIntegrationTest.java`. Giữ `SortTraceBuilder` và `SortFrame` v1 cho ba sort cũ.

- [ ] Kiểm thử context đệ quy, range trái/phải, giá trị mảng phụ, write đang chờ/đã xác nhận và copy-back; UI không đổi mảng nguồn trước bước copy-back.
- [ ] Chạy bộ 6 mảng, chiều tăng/giảm, độ dài chẵn/lẻ, nhiều lần gọi; xác nhận vùng merge và toàn mảng bằng snapshot.
- [ ] Chạy `mvn test`, thử UI cả năm sort, kiểm tra `/api/debug` và `/api/debug/steps`, rồi `git diff --check`.
- [ ] Duyệt cổng Merge; cập nhật giới hạn được hỗ trợ và các biến thể chưa có trong tài liệu.

## Review Focus xuyên suốt

1. Mã có cùng kết quả trên một mảng nhưng sai logic ở mảng khác không được nhận nhầm là sort đúng.
2. Biến trùng tên ở scope khác hoặc hai mảng có tên/alias gần giống phải liên kết đúng khai báo.
3. Snapshot `BEFORE_LOCATION` không được trình bày như thao tác đã hoàn tất.
4. Lần gọi đệ quy cùng method/cùng độ sâu ở các thời điểm khác nhau không được trộn trace.
5. Mảng bị cắt, timeout, hoặc không đọc được vẫn có trạng thái rõ ràng; không báo hoàn tất đã kiểm chứng.
