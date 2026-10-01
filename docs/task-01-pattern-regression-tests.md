# Task 1: Regression tests cho nhan dien pattern

## Muc tieu

Chot hanh vi truoc khi sua binding: hai local cung ten nhung khac khai bao khong
duoc ghep thanh mot accumulator. Mot accumulator dung chung qua nested blocks
van phai duoc nhan dien.

## Test va cach chay

`PatternBindingRegressionTest` gom mau am (sum/mid khac scope), mau duong
(sum cung khai bao), metadata ID, va gioi han cu phap tuong duong.

```powershell
cd backend
mvn test "-Dtest=PatternBindingRegressionTest"
```

Lan chay truoc khi sua: 3 tests, 1 failure o
`doesNotCombineDifferentAccumulatorsWithTheSameName`. Backend tra sliding-window
cho hai bien sum doc lap. Day la loi da tai hien, khong phai chi suy luan tu code.

Sau task 2, test do phai pass. Mau `r > l` thay cho `l < r` hien van ky vong
khong nhan dien: task 4 moi chuan hoa bieu thuc. Khong danh dau skipped hay
mo rong rule ngoai pham vi task 1-3.

## Review

Test bao ve ca false positive va true positive. Khong coi cac fixture cu thieu
khai bao bien la chuong trinh hop le: cac positive fixture lien quan da duoc
bo sung parameter/local de phu hop yeu cau binding moi.
Ket qua toan bo test cuoi duoc ghi trong doc task 3.
