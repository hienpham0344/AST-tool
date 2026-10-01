# Buoc 2: Nhan dien thuat toan tu AST

## Muc tieu va luong xu ly

Buoc nay them goi y thuat toan vao API, chua ve con tro/window tren UI.
FE hien tai van hien thi structured variables nhu truoc.

1. Upload file Java qua `POST /api/debug/steps`, multipart field `file`.
2. `AstAnalyzer` parse source thanh `CompilationUnit`.
3. Compiler va JDI chay chuong trinh, thu `steps` va `warnings` nhu cu.
4. `AlgorithmPatternAnalyzer` duyet AST da parse, tim cac vong `while`/`for`.
5. Moi vong lap duoc thu lan luot: binary search, sliding window, two pointers.
   Chi tra toi da mot hint cho moi vong; cac vong khac van co hint rieng.
6. API tra `steps`, `warnings`, `algorithmHints`. API `/api/debug` giu dang mang cu.

Khong dua vao ten bien, comment hay chuoi ky tu. Vi du `lo`, `hi`, `pivot`
duoc gan vai tro `left`, `right`, `mid` nho cach su dung trong bieu thuc.
Pham vi hint la dong bat dau/ket thuc cua vong lap, tinh tu 1 va gom ca hai dau.

## Dieu kien nhan dien

| Mau | Bang chung AST bat buoc | visualPlan |
| --- | --- | --- |
| Binary search | `lo < hi` hoac `lo <= hi`; midpoint tu hai can; so sanh `a[mid]` trong if; cap nhat co dieu kien `lo = mid + 1`, `hi = mid - 1` hoac `hi = mid` | array-pointers |
| Two pointers | So sanh hai chi so; truy cap cung mang tai ca hai; chi so trai tang 1, phai giam 1 | array-pointers |
| Sliding window (sum) | Chi so phai tang 1 va `< a.length`; `sum += a[right]`; cung sum tru phan tu roi cua so co dieu kien | array-window |

Midpoint ho tro `(lo + hi) / 2`, `lo + (hi - lo) / 2`, va dich phai 1 bit.
Tang/giam chi so ho tro `++`, `--`, `+= 1`, `-= 1`, `i = i + 1`, `i = i - 1`.
Ngoac don duoc bo qua khi so khop bieu thuc.

Cua so bien do rong: tru `a[left]` va tang left trong outer loop co if, hoac
trong while con truc tiep co dieu kien tham chieu accumulator.
Cua so co kich thuoc k: tru `a[right - k]` trong if, k khong duoc cap nhat trong loop.
Hint cua so k co `windowSize`, khong tao bien left gia. FE can tinh bien trai
dua vao thoi diem snapshot; khong mac dinh snapshot la trang thai sau cau lenh.

## Vi du chay thu

Luu source sau thanh `SearchSample.java` va upload bang UI hoac curl:

```java
public class SearchSample {
  public static void main(String[] args) {
    int[] nums = {1, 3, 5, 7};
    int lo = 0, hi = nums.length - 1, target = 5;
    while (lo <= hi) {
      int m = lo + (hi - lo) / 2;
      if (nums[m] == target) break;
      if (nums[m] < target) lo = m + 1;
      else hi = m - 1;
    }
  }
}
```

```powershell
curl.exe -s -F "file=@SearchSample.java" http://localhost:8080/api/debug/steps
```

Hint ky vong: `type: binary-search`, `confidence: 0.9`,
`variables: {array: nums, left: lo, right: hi, mid: m}`, `startLine: 5`, `endLine: 10`.
Confidence la diem heuristic co dinh theo rule, khong phai xac suat da hieu chuan.

Hai mau body khac de thay vao main:

```java
int[] nums = {1, 2, 3, 4};
int a = 0, b = nums.length - 1;
while (a < b) {
  int temp = nums[a]; nums[a] = nums[b]; nums[b] = temp;
  a++; b--;
}
// Expected: two-pointers, left=a, right=b, array=nums.
```

```java
int[] nums = {1, 2, 3, 4};
int k = 2, total = 0;
for (int end = 0; end < nums.length; end++) {
  total += nums[end];
  if (end >= k) total -= nums[end - k];
}
// Expected: sliding-window, right=end, windowSize=k, accumulator=total.
```

## Cach review va test

Chay `mvn test` trong `backend` voi JDK 17 va Maven.
`AlgorithmPatternAnalyzerTest` kiem tra doi ten bien, midpoint khac nhau,
for/while, cua so co dinh/bien do rong, range, tinh lap lai va cac mau khong du bang chung.
Test am bao gom khac mang, thieu cap nhat, khac accumulator, k bi thay doi,
comment/string, nested loop, lambda, method khac nhau, loop khong condition,
va cap nhat chi xuat hien trong phan khoi tao for (khong phai moi lan lap).
`MultipartApiIntegrationTest` compile/chay binary search qua JDI va kiem tra
hint that cung voi execution steps. Test API cu van kiem tra mang legacy va hint rong.

Ket qua kiem tra buoc 2: `mvn test` thanh cong, 78 tests, 0 failures, 0 errors,
0 skipped. Trong do co 15 tests rieng cho nhan dien AST va 1 test API moi chay
binary search qua compiler/JDI. Bon file Java thay doi duoc format bang Spotless.

## Gioi han can biet

- Day la nhan dien cu phap, chua co symbol resolution/dataflow hay chung minh thuat toan dung.
  Khong kiem tra mang da sort, dieu kien dung, gia tri k, hay tinh dung cua moi nhanh.
- Hint co the thuoc method chua duoc goi. Khong coi hint la bang chung da thuc thi.
- Chi ho tro mang truy cap qua ten don gian; chua ho tro string, list, field access,
  alias, helper method, do-while, two pointers cung chieu, sliding window dung map/set.
- Midpoint hien can khai bao trong loop; gan lai bien mid khai bao ngoai loop chua ho tro.
- Sliding window hien ho tro accumulator `+=`/`-=` voi array access truc tiep;
  `sum = sum + ...`, `a[left++]`, va cac dang tuong duong khac chua ho tro.
- Khong gom bang chung tu cac loop khac, lambda hay class long nhau vao loop cha.
  Chua phan tich moi truong hop shadowing hoac tai gan bien, nen hint van co the nham.
- FE nen fallback sang structured variables khi khong co hint/khong biet visualPlan.
  Buoc tiep theo moi them visual events gan voi tung execution step.
