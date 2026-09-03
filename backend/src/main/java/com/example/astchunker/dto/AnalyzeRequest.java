package com.example.astchunker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload gui tu frontend (textarea nhap code Java).
 *
 * CHU Y KHI XAY DUNG LOGIC:
 * 1) Gioi han do dai (maxLength) la BAT BUOC. Neu khong gioi han, mot payload
 *    vai chuc MB code se khien JavaParser ton rat nhieu CPU/memory -> de bi
 *    lam DoS server chi bang 1 request. 200_000 ky tu (~200KB) la muc de xuat
 *    ban dau, chinh lai theo nhu cau thuc te.
 * 2) Neu sau nay ho tro nhieu ngon ngu (khong chi Java), them field `language`
 *    o day va dispatch sang service tuong ung trong Controller/Service layer.
 */
public class AnalyzeRequest {

    @NotBlank(message = "code khong duoc de trong")
    @Size(max = 200_000, message = "code vuot qua gioi han cho phep (200,000 ky tu)")
    private String code;

    public AnalyzeRequest() {
    }

    public AnalyzeRequest(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
