# Task 2: Binding bien theo lexical scope

## Thay doi

Them `LocalVariableBindings`: resolve NameExpr ve ID khai bao local/parameter.
Sau khi rule tim duoc candidate, moi role phai co tham chieu resolve duoc va
tat ca tham chieu cung ten cua role trong callable cua loop phai ve cung mot ID.
Khong du bang chung thi khong publish hint.

Resolver dung ancestor scope va vi tri line/column cua declaration/reference,
khong dung ten bien hoac do rong khoang dong de quyet dinh danh tinh.

Ho tro: parameter method/constructor, local trong block, for initializer,
foreach variable, catch parameter. Khong vuot boundary lambda/type/callable.
Parameter catch/variable for khong duoc coi la visible sau scope cua no.
Field, alias, symbol tu thu vien, try resource va switch local khong co block
chua duoc ho tro; unresolved duoc tu choi thay vi doan.

## Vi du loi duoc sua

```java
for (int r = 0; r < a.length; r++) {
  { int sum = 0; sum += a[r]; }
  { int sum = 0; if (r >= k) sum -= a[r-k]; }
}
```

Ket qua moi: khong co sliding-window hint. Neu sum duoc khai bao mot lan truoc
for va hai block cung dung bien do thi van co hint.

## Kiem tra va review

`LocalVariableBindingsTest` kiem tra sibling blocks cung dong, parameter nested
block, boundary lambda/local class, for/catch scope, declaration sau reference,
va hai method co parameter trung ten. Test regression con kiem tra mid khac scope.

Gioi han: day la lexical resolver cho tap con Java dang ho tro, khong phai full
Java symbol solver/dataflow analyzer. Rule van tim candidate bang cu phap va
ten, nhung chi chap nhan candidate sau binding validation. Neu mot ten role
duoc dung cho khai bao khac trong nested block/loop, candidate bi tu choi bao thu;
co the bo sot pattern hop le. Chua kiem tra branch/update relationship (task 5).

Khong thay doi cach JDI map locals trong task nay. Source dong don nhieu scope
van co gioi han runtime; binding AST chinh xac khong tao them breakpoint.
