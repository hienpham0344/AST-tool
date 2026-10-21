> Cần visualize từng thao tác, biến temp riêng và vòng đời scope? Dùng
> `POST /api/debug/steps?traceMode=OPERATIONS` và
> [hướng dẫn operation trace](frontend-operation-trace-guide.md).
> Phần LINE_BREAKPOINT/BEFORE_LOCATION bên dưới là chế độ mặc định cũ; không
> áp dụng nguyên renderer đó cho response OPERATIONS schemaVersion=2.

# Hợp đồng JSON runtime cho frontend độc lập

Tài liệu này dành cho agent frontend ở project khác. Nguồn dữ liệu visualize
thuật toán là `POST /api/debug/steps`, không phải endpoint `/api/debug` cũ.
Frontend nhận một response JSON sau khi backend parse, compile, chạy target
qua JDI và xây trace. Endpoint hiện không stream từng bước.

## Gọi API

Backend mặc định chạy ở `http://localhost:8080`. Gửi một file Java bằng
`multipart/form-data` với field tên `file`:

```bash
curl -F 'file=@SearchSample.java' http://localhost:8080/api/debug/steps
```

```js
async function fetchTrace(file, baseUrl = 'http://localhost:8080') {
  const form = new FormData();
  form.append('file', file, file.name);
  const response = await fetch(`${baseUrl}/api/debug/steps`, {
    method: 'POST', body: form
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.message || data.error?.message || `HTTP ${response.status}`);
  }
  if (!Array.isArray(data.steps) || !Array.isArray(data.algorithmHints)) {
    throw new Error('Response runtime không đúng hợp đồng');
  }
  return data;
}
```

Không tự đặt header `Content-Type`: trình duyệt cần thêm multipart boundary.
File phải có đuôi `.java`, không rỗng, tối đa 200.000 byte. Target cần một
`public static void main(String[] args)` trong source Java tự chứa. CORS hiện
cho phép mọi origin trên `/api/**` trong cấu hình phát triển. Lỗi HTTP 400,
422 hoặc 500 thường có `message`, đôi khi có `problems: string[]`; hiển thị
chúng riêng với `warnings` của response thành công.

## Response tổng thể

```json
{
  "algorithmHints": [],
  "steps": [],
  "warnings": []
}
```

| Trường | Ý nghĩa |
| --- | --- |
| `algorithmHints[]` | Gợi ý nhận diện tĩnh từ AST; có thể tồn tại dù nhánh code chưa chạy. Danh sách rỗng không chứng minh source không chứa thuật toán. |
| `steps[]` | Các lần chạm breakpoint theo thứ tự `sequence`, cùng biến JDI và trạng thái visualize của chính snapshot đó. Có thể rỗng. |
| `warnings[]` | Vấn đề không gây HTTP error, ví dụ trace thiếu breakpoint. Cần hiển thị cho người dùng. |

`POST /api/debug` trả mảng phẳng `ObservationResult[]`, không có hints/states/
events. `POST /api/analyze` trả chunks/stats; `POST /api/ast/parse` trả cây AST.
Các định dạng này khác response runtime, không đưa thẳng vào cùng renderer.

## `algorithmHints[]`: nhận diện mã nguồn

Mỗi hint có `type`, `confidence`, `visualPlan`, `evidence[]`, `variables`,
`startLine`, `endLine`, `patternAstNodeId`, `methodAstNodeId`,
`variableDeclarationIds` và metadata riêng của một họ thuật toán.

- `type` là định danh thuật toán. `confidence` là điểm heuristic trong `[0,1]`,
  không phải xác suất đúng đã hiệu chuẩn.
- `variables` ánh xạ vai trò ngữ nghĩa sang tên biến source, ví dụ
  `{ "array": "nums", "left": "lo", "right": "hi", "mid": "m" }`.
  `variableDeclarationIds` ánh xạ cùng vai trò tới ID khai báo AST. Dùng ID
  để phân biệt biến trùng tên ở các scope.
- `startLine`/`endLine` tính từ 1, gồm cả hai đầu; là phạm vi mã nguồn,
  không phải thời gian thực thi. AST ID chỉ ổn định khi parse lại cùng source.
- `evidence[]` mô tả bằng chứng tĩnh. Hint không chứng minh thuật toán đúng,
  đầu vào thỏa tiền điều kiện, hay nhánh đó đã chạy.

| `type` | Metadata tĩnh | Frame runtime |
| --- | --- | --- |
| `bubble-sort`, `selection-sort`, `insertion-sort` | `sort` | `sortFrame` |
| `quick-sort` | `quick` | `quickFrame` |
| `merge-sort` | `merge` | `mergeFrame` |
| `heap-sort` | `heap` | `heapFrame` |
| `activity-selection` | `activitySelection` | `activitySelectionFrame` |
| `bfs` | `bfs` | `bfsFrame` |
| `dfs` | `dfs` | `dfsFrame` |
| `two-pointers` | `twoPointers` | `twoPointersFrame` |
| `sliding-window` | `slidingWindow` | `slidingWindowFrame` |
| `linear-search` | `linearSearch` | `linearSearchFrame` |
| `prefix-sum` | `prefixSum` | `prefixSumFrame` |
| `binary-search` | Chưa có metadata riêng | Chưa có frame riêng; đọc `array`, `pointers`, `range` |

Metadata/frame không áp dụng được thường bị bỏ khỏi JSON khi Java value là
`null`. Đừng giả định mọi hint có frame. Binary search dùng renderer con trỏ
chung hoặc fallback biến runtime.

## `steps[]`: thứ tự và thời điểm ảnh chụp

Một step là một lần JDI breakpoint được hit, không phải một vòng lặp hoặc
một statement đã chạy xong.

| Trường | Ý nghĩa |
| --- | --- |
| `sequence` | Thứ tự thu nhận trong response, bắt đầu từ 1. Cùng AST ID có thể xuất hiện nhiều lần. |
| `lineNumber`, `statementAstNodeId`, `statementKind`, `code` | Vị trí và source đại diện; `lineNumber` có thể là `-1` nếu JDI thiếu thông tin. |
| `startLine`, `endLine`, `startColumn`, `endColumn` | Phạm vi AST để highlight, không phải ranh giới bytecode chính xác. |
| `snapshotPhase` | Hiện là `BEFORE_LOCATION`: chụp trước bytecode tại breakpoint, không cam kết trước toàn bộ statement. |
| `granularity` | Hiện là `LINE_BREAKPOINT`; nhiều statement cùng dòng có thể chỉ có một breakpoint. |
| `context` | Thread, class, method, method signature, stack depth, code index; `invocationId` có thể có hoặc vắng. |
| `variables[]` | Local nhìn thấy trong suspended frame, không phải mọi biến trong chương trình. |
| `visualStates[]` | 0, 1 hoặc nhiều state cho hint áp dụng tại snapshot này. |
| `visualEvents[]` | Chênh lệch giữa snapshot hiện tại và snapshot trước nếu đủ điều kiện so sánh. |

Khi `x = 2;` đang highlight, snapshot có thể còn `x = 1`. Không tự tạo
snapshot cuối khi VM kết thúc; step cuối chưa chắc là trạng thái cuối chương
trình. Call, bytecode không có breakpoint, exception hoặc thread khác có thể
diễn ra giữa hai step. `sequence` không phải thứ tự toàn cục của mọi thread.

Mỗi item `variables[]` có `astNodeId`, `variableName`, `declaredType`,
`runtimeValue` (chuỗi để hiển thị), `visualType`, `visualValue` (giá trị JSON có
cấu trúc), `lineNumber`. Dùng `visualValue`, không parse `runtimeValue` để
tính toán. Java `null`, giá trị không đọc được và biến chưa vào scope là ba
trạng thái khác nhau. `arrayObjectId` và `objectId` là dữ liệu nội bộ không
được serialize tại cấp `variables[]`.

## `visualStates[]`: nguồn dữ liệu để vẽ một step

State gồm `id`, `hintIndex`, `algorithm`, `visualPlan`, `status`, `array`,
`pointers`, `scalars`, `range`, `notes[]` và frame riêng nếu có. `hintIndex`
trỏ tới `algorithmHints[hintIndex]` trong cùng response. `id` thường là
`hint-N`, chỉ có ý nghĩa trong response này, không phải ID object hoặc lần
gọi hàm. Một step có thể có nhiều state từ các loop lồng nhau.

- `array`: `{variableName, astNodeId, status, values, length, truncated}`.
  `values` là prefix được chụp, có thể ngắn hơn `length`; phần tử `null` là
  Java null hợp lệ. `truncated: true` nghĩa là chưa đủ dữ liệu để kết luận
  trên toàn mảng. `AVAILABLE` với `values: []` khác `NULL` hay `MISSING`.
  Một số frame có dữ liệu phụ riêng, ví dụ `mergeFrame.auxiliary`.
- `pointers`: ánh xạ vai trò như `left`, `right`, `mid`, `scan` sang
  `{variableName, astNodeId, index, status}`. Chỉ gắn marker vào ô khi status
  `VALID`. `NOT_CAPTURED` là index hợp lệ nhưng ngoài prefix; `OUT_OF_BOUNDS`
  giữ index để báo lỗi, không được clamp. `MISSING`, `NULL`, `UNAVAILABLE`,
  `INVALID`, `ARRAY_UNAVAILABLE` không phải vị trí vẽ được.
- `scalars`: ánh xạ `accumulator`, `windowSize`, `key` sang
  `{variableName, astNodeId, value, status}`.
- `range`: `{kind, start, endExclusive, status}` theo quy ước nửa mở
  `[start, endExclusive)`. Chỉ tô các ô đã chụp khi status `VALID`; `EMPTY`
  là khoảng rỗng; `UNKNOWN`/`INVALID` không được tô như khoảng chắc chắn.
  `POINTER_SPAN` và `FIXED_SIZE_CANDIDATE` là khoảng ứng viên, không chứng
  minh phần tử đã cộng vào accumulator hay là tập tìm kiếm chính xác.
- `status` cấp state thường là `READY`, `PARTIAL`, `UNAVAILABLE`. `READY`
  chỉ nghĩa là đủ dữ liệu để vẽ, không có nghĩa thuật toán đúng. `notes[]`
  giải thích giới hạn cụ thể của state.

| Frame | Thông tin chính |
| --- | --- |
| `sortFrame` | `phase`, `comparison`, `pendingMutation`, `completedMutation`, `sortedRegion` |
| `quickFrame` | `low/high`, pivot, scan/store, comparison, mutation, `partitionStatus`, `fullArrayStatus` |
| `mergeFrame` | `low/mid/high`, pointer đọc/ghi, `auxiliary`, comparison, mutation, `mergedRangeStatus`, `fullArrayStatus` |
| `heapFrame` | `heapEnd` (exclusive), node/children/chosen, comparison, mutation, `heapStatus`, `suffixStatus`, `fullArrayStatus` |
| `activitySelectionFrame` | candidate/start/end/lastEnd, overlap policy, decision, selected indices, ordering và kiểm chứng |
| `twoPointersFrame` | movement, hai index/value/delta, `effectStatus` |
| `slidingWindowFrame` | loại cửa sổ, hai biên, giá trị thêm/bỏ, accumulator, `effectStatus` |
| `linearSearchFrame` | index, current/target value, `comparisonStatus` |

`pendingMutation` hoặc `PENDING` là thao tác chưa được xác nhận tại điểm
chụp trước lệnh. Chỉ `completedMutation` hoặc trạng thái
`OBSERVED_AFTER_WRITE`/trạng thái kiểm chứng tương ứng mới là bằng chứng từ
snapshot sau. `UNKNOWN`, `TRUNCATED`, `VIOLATED`, `NOT_SORTED` không phải
thành công. `MATCH` của Linear Search là so sánh ứng viên tại snapshot,
chưa chứng minh nhánh return đã chạy. `VERIFIED_SORTED` và
`fullArrayStatus` chỉ kiểm tra dữ liệu runtime đã chụp đủ, không chứng minh
mọi input.

### Ví dụ JSON rút gọn

Đây là **các trường cần đọc**, không phải toàn bộ field của record Java.
`visualStates` có thể rỗng ở step đầu và xuất hiện ở step sau:

```json
{
  "algorithmHints": [
    {
      "type": "binary-search",
      "confidence": 0.9,
      "visualPlan": "array-pointers",
      "variables": {"array": "nums", "left": "lo", "right": "hi", "mid": "m"},
      "patternAstNodeId": "ast-WhileStmt-5-5-10-5"
    }
  ],
  "steps": [
    {
      "sequence": 1,
      "snapshotPhase": "BEFORE_LOCATION",
      "variables": [],
      "visualStates": [],
      "visualEvents": []
    },
    {
      "sequence": 2,
      "snapshotPhase": "BEFORE_LOCATION",
      "variables": [],
      "visualStates": [
        {
          "id": "hint-0",
          "hintIndex": 0,
          "algorithm": "binary-search",
          "status": "READY",
          "array": {"status": "AVAILABLE", "values": [1, 3, 5], "length": 3, "truncated": false},
          "pointers": {"mid": {"index": 1, "status": "VALID"}},
          "range": {"kind": "POINTER_SPAN", "start": 0, "endExclusive": 3, "status": "VALID"},
          "notes": ["POINTER_SPAN_DOES_NOT_DECLARE_SEARCH_BOUND_CONVENTION"]
        }
      ],
      "visualEvents": [
        {"stateId": "hint-0", "type": "STATE_ENTERED", "target": "state", "fromSequence": null, "toSequence": 2, "before": null, "after": null}
      ]
    }
  ],
  "warnings": []
}
```

Không hardcode số step hoặc thứ tự xuất hiện của state theo ví dụ.

## `visualEvents[]`: chuyển động tùy chọn

Event có `{stateId, type, target, fromSequence, toSequence, before, after}`.
Các type hiện có: `STATE_ENTERED`, `STATE_RESET`, `STATE_LEFT`,
`ARRAY_CHANGED`, `POINTER_CHANGED`, `VALUE_CHANGED`, `RANGE_CHANGED`.
Lifecycle event có `before`/`after` bằng `null`; `*_CHANGED` mang dữ liệu
theo cùng shape với field state tương ứng.

Event chỉ so sánh hai step kề nhau khi cùng thread/class/method/signature/
stack depth và `codeIndex` tăng. Loop quay về đầu, chuyển frame hoặc thiếu
bằng chứng sẽ tạo reset hoặc không có delta. Event là **khác biệt quan sát**,
không chứng minh statement đang highlight gây ra thay đổi. Khi người dùng
seek/previous, render trực tiếp `visualStates` của step đích, không replay
từ đầu. Có thể animate next liên tiếp bằng event, nhưng state đích luôn là
nguồn sự thật khi event thiếu.

## Checklist tích hợp cho agent frontend

1. Fetch `/api/debug/steps` rồi kiểm tra `steps`, `algorithmHints`, `warnings`
   là mảng. Loading kết thúc khi toàn request xong; endpoint chưa streaming.
2. Duyệt **mọi** `visualStates` của step đang chọn. Ghép với hint qua
   `hintIndex`; không mặc định chỉ có `visualStates[0]`.
3. Dispatch renderer theo `state.algorithm` và frame tương ứng. Binary search
   dùng `array`/`pointers`/`range` chung. Unknown type hoặc state rỗng thì
   fallback `variables[]`, không giữ hình của step trước.
4. Phân biệt pending, observed, verified, missing, truncated, out-of-bounds
   bằng status/notes. Không gán 0 cho `null`, không nội suy giá trị từ step cũ.
5. Hiển thị `warnings`; giữ JSON gốc cho debug/export. Nếu frontend tạo view
   model riêng, tự version hóa nó: backend chưa có `schemaVersion` cấp response.
   `schemaVersion` trong một số metadata chỉ áp dụng cho họ thuật toán đó.

Mẫu Java để tích hợp nằm ở `backend/examples/sort/`,
`backend/examples/greedy/`, `backend/examples/twopointers/`,
`backend/examples/slidingwindow/` và `backend/examples/linearsearch/`.
Test hợp đồng JSON: `MultipartApiIntegrationTest`.


## BFS: đồ thị, hàng đợi FIFO và visited

Xem [guide BFS](bfs-test-guide.md) và hai fixture trong `backend/examples/graph/`.
Hint `type = "bfs"`, `visualPlan = "graph-bfs"`; metadata `bfs` có
`schemaVersion = 1`, `representation = "ADJACENCY_MATRIX" | "ADJACENCY_LIST"`,
`declarationIds`, `operationAstNodeIds`, `evidence`, `sourceLiteral` (nullable).
Các role là `graph`, `queue`, `visited`, `current`, `neighbor`, `source`;
`source` vắng trong map khi dùng literal.

`bfsFrame` chứa:

| Trường | Ý nghĩa |
| --- | --- |
| `invocationId`, `graphObjectId`, `queueObjectId`, `visitedObjectId` | Nhận dạng để giữ tính liên tục trong một trace. |
| `representation`, `source`, `currentVertex` | Biểu diễn và đỉnh nguồn/đang xét; scalar có thể null. |
| `graph` | `{vertexCount, edges: [{from, to}], status, truncated}`; giữ hướng và cạnh trùng quan sát được. |
| `queue` | Mảng đỉnh theo thứ tự đầu đến đuôi FIFO. |
| `visitedVertices` | Các chỉ số có visited=true, theo thứ tự chỉ số; không phải thứ tự duyệt. |
| `examiningEdge` | `{from, to}` ứng viên đang kiểm tra trong guard/nhánh, hoặc null; chưa chứng minh cạnh tồn tại/nhánh được chọn. |
| `graphStatus`, `queueStatus`, `visitedStatus` | `AVAILABLE`, `UNKNOWN`, `TRUNCATED` cho từng projection. |
| `snapshotPhase`, `notes` | `BEFORE_LOCATION` và giới hạn diễn giải. |

State `status` là `READY`, `PARTIAL` hoặc `TRUNCATED`; `array`/`range` có thể
null với BFS. Dùng `bfsFrame` để dựng graph và queue, không ép sang renderer mảng.
Queue/visited có thể là mảng rỗng vì chưa đọc được: luôn kiểm tra status.
Mỗi variable projection dùng `visualType = bfs-graph | bfs-queue | bfs-visited`;
queue/visited payload là `{values, length, status, truncated}`.

Sự kiện `ENQUEUE`/`DEQUEUE` có `target = "queue"`, before/after là toàn bộ FIFO;
`VISIT` có `target = "visited"`, before/after là các chỉ số đã thăm. Chỉ sinh khi
hai snapshot đầy đủ cho thấy một thay đổi không đơn nghĩa, cùng thread,
invocation và identities. Không khẳng định câu lệnh tại breakpoint hiện tại
đã thực thi, không tự suy ra hoàn tất hoặc đường đi ngắn nhất.

Giới hạn: 100 đỉnh, 100 phần tử queue, 1000 cạnh và ngân sách đọc 1000 giá trị
lồng nhau; đồ thị lớn hoặc collection không hỗ trợ trả trạng thái thiếu/cắt cụt.
BFS này chưa hỗ trợ lưới 2D hoặc đỉnh object/string; detector chỉ nhận các mẫu
FIFO và đánh dấu trước enqueue đã chứng minh (xem guide).

## Prefix Sum: hai mảng và tổng đoạn

Hint type `prefix-sum` có metadata `prefixSum` schemaVersion=1, representation
`SAME_LENGTH` hoặc `ZERO_SENTINEL`, declarationIds theo role data/prefix/index và
operationAstNodeIds của allocation/seed/recurrence/loop/indexUpdate. Các query đã
được chứng minh nằm trong prefixSum.queries. Không suy thuật toán từ tên biến.

Đọc mọi visualStates theo hintIndex; `prefixSumFrame` dành cho LINE_BREAKPOINT
và giữ ngữ nghĩa BEFORE_LOCATION. Ô đích của recurrence tại location hiện tại
vẫn pending. Giá trị primitive mặc định 0 không chứng minh ô đã xây dựng.
OPERATIONS vẫn dùng generic steps/effects; không lọc bỏ code thường theo hint.

Xem [hướng dẫn Prefix Sum cho frontend](prefix-sum-frontend-guide.md), bao gồm
fixture, hai layout, query, trạng thái thiếu dữ liệu và overflow int/long.

## DFS đệ quy: graph, visited và caller frames

Hint type `dfs` có metadata `dfs` schemaVersion=1, representation ADJACENCY_MATRIX
hoặc ADJACENCY_LIST, declarationIds graph/visited/current/neighbor và các anchor
mark/neighborLoop/guard/recursiveCall trong operationAstNodeIds. Method identity
là methodAstNodeId; hint không chứng minh method đã được chạy.

Legacy visualStates có dfsFrame theo hintIndex. Vẫn giữ BEFORE_LOCATION: mark
hiện tại chưa được coi là thực thi, candidate edge chưa xác nhận lời gọi mới.
Render graph/visited từ projection quan sát được và giữ status thiếu/truncated.
OPERATIONS giữ generic steps/effects và invocation IDs, không có dfsFrame legacy.

Xem [hướng dẫn frontend DFS](dfs-frontend-guide.md) cho fixture và cách giữ frame
caller khi đi sâu rồi quay lại, không sort trace theo số dòng.
