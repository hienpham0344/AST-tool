package com.example.astchunker.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Mot "chunk" trong cay cau truc code: package, import, class/interface/enum/record, constructor,
 * method, field. Giu cung hinh dang JSON ma frontend (java-structure-chunker.html) dang render
 * (kind, name, startLine, endLine, code, children) de KHONG phai sua UI.
 *
 * <p>CHU Y KHI MO RONG: - Neu them loai chunk moi (vi du: static initializer block, annotation
 * member, enum constant rieng le...) thi chi can them "kind" moi va dam bao Service tao dung
 * CodeChunkDto tuong ung — frontend hien tai render theo "kind" dang string nen khong can sua
 * schema. - "returnType" / "fieldType" duoc gop chung vao "type" o day cho don gian; neu can phan
 * biet ro rang hon (vi du de highlight rieng), tach thanh field khac.
 */
public class CodeChunkDto {

  private String
      kind; // package | import | class | interface | enum | record | constructor | method | field
  private String name; // ten hien thi: "com.example.Foo", "addUser(String name)", "capacity", ...
  private String type; // returnType (method) hoac fieldType (field) - null neu khong ap dung
  private int startLine;
  private int endLine;
  private String code; // source code goc cua chunk nay (day du, khong bi cat)
  private List<CodeChunkDto> children = new ArrayList<>();

  public CodeChunkDto() {}

  public CodeChunkDto(String kind, String name, int startLine, int endLine, String code) {
    this.kind = kind;
    this.name = name;
    this.startLine = startLine;
    this.endLine = endLine;
    this.code = code;
  }

  // --- getters / setters ---

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public int getStartLine() {
    return startLine;
  }

  public void setStartLine(int startLine) {
    this.startLine = startLine;
  }

  public int getEndLine() {
    return endLine;
  }

  public void setEndLine(int endLine) {
    this.endLine = endLine;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public List<CodeChunkDto> getChildren() {
    return children;
  }

  public void setChildren(List<CodeChunkDto> children) {
    this.children = children;
  }

  public void addChild(CodeChunkDto child) {
    this.children.add(child);
  }
}
