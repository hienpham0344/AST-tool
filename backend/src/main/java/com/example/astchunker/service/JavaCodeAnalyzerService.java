package com.example.astchunker.service;

import com.example.astchunker.dto.AnalyzeResponse;
import com.example.astchunker.dto.CodeChunkDto;
import com.example.astchunker.exception.CodeParseException;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.ImportDeclaration;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service chiu trach nhiem chinh: nhan source code Java (String) -> tra ve cay CodeChunkDto.
 *
 * Khac voi ban demo o frontend (dung regex/brace-matching), o day dung THAT SU mot
 * trinh parser Java (JavaParser) nen ket qua chinh xac 100% ve mat cu phap (class long,
 * generic, lambda, record, sealed class, annotation... deu duoc AST nhan dien dung),
 * thay vi doan heuristic truoc chi la "gan dung".
 */
@Service
public class JavaCodeAnalyzerService {

    // CHU Y: JavaParser instance co the tai su dung (thread-safe cho method parse()),
    // nhung ParserConfiguration nen duoc thiet lap 1 lan luc khoi tao, khong tao lai
    // moi request de tranh overhead khong can thiet.
    private final JavaParser javaParser;

    public JavaCodeAnalyzerService() {
        ParserConfiguration config = new ParserConfiguration()
                // CHU Y: chon dung LanguageLevel neu code nguoi dung dung cu phap moi
                // (record, sealed, pattern matching switch...). Neu dat level qua thap,
                // JavaParser se bao loi cu phap voi code hop le nhung moi.
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(config);
    }

    public AnalyzeResponse analyze(String sourceCode) {
        ParseResult<CompilationUnit> result = javaParser.parse(sourceCode);

        // CHU Y: JavaParser co the "parse mot phan" (partial success) voi mot so loi.
        // Neu result.isSuccessful() == false NHUNG van co CompilationUnit, ban co the
        // chon: (a) van tra ve chunk cho phan parse duoc + warnings, hoac (b) tu choi
        // hoan toan nhu code duoi day. Chon (a) neu muon UX "khoan dung" hon.
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            List<String> problems = new ArrayList<>();
            for (Problem p : result.getProblems()) {
                problems.add(formatProblem(p));
            }
            throw new CodeParseException("Code Java khong hop le, khong the parse.", problems);
        }

        CompilationUnit cu = result.getResult().get();
        List<CodeChunkDto> chunks = new ArrayList<>();

        // 1) package
        cu.getPackageDeclaration().ifPresent(pkg -> chunks.add(buildPackageChunk(pkg, sourceCode)));

        // 2) imports
        for (ImportDeclaration imp : cu.getImports()) {
            chunks.add(buildImportChunk(imp, sourceCode));
        }

        // 3) top-level type declarations (class/interface/enum/record)
        // CHU Y: 1 file .java CO THE co nhieu top-level type (vi du 1 public class +
        // vai class khong public cung file) -> phai duyet TAT CA, khong chi lay type dau tien.
        for (TypeDeclaration<?> type : cu.getTypes()) {
            chunks.add(buildTypeChunk(type, sourceCode));
        }

        Map<String, Integer> stats = new LinkedHashMap<>();
        countChunks(chunks, stats);

        // CHU Y: neu parser "recover" duoc tu loi nho (vi du thieu dau ; o cho nao do
        // nhung JavaParser van co the doan y), ban nen dua canh bao vao day thay vi
        // im lang tra ve ket qua sai lech.
        List<String> warnings = new ArrayList<>();

        return new AnalyzeResponse(chunks, stats, warnings);
    }

    // ---------- Xay dung tung loai chunk ----------

    private CodeChunkDto buildPackageChunk(PackageDeclaration pkg, String source) {
        return new CodeChunkDto("package", pkg.getNameAsString(),
                lineOf(pkg, true), lineOf(pkg, false), rangeText(pkg, source));
    }

    private CodeChunkDto buildImportChunk(ImportDeclaration imp, String source) {
        String name = (imp.isStatic() ? "static " : "") + imp.getNameAsString() + (imp.isAsterisk() ? ".*" : "");
        return new CodeChunkDto("import", name, lineOf(imp, true), lineOf(imp, false), rangeText(imp, source));
    }

    /**
     * Ham DE QUY: mot TypeDeclaration (class/interface/enum/record) co the chua
     * nested type khac ben trong -> phai goi lai chinh no cho cac nested type.
     *
     * CHU Y KHI MO RONG:
     * - Hien tai bo qua anonymous class (new Foo() { ... }) va local class (khai bao
     *   trong than method) vi chung khong nam trong cu.getTypes()/getMembers() cua
     *   class cha theo cach don gian nay. Neu can, phai dung mot Visitor
     *   (VoidVisitorAdapter<Void>) duyet toan bo AST thay vi chi duyet getMembers().
     * - "kind" duoc suy ra tu class cu the cua Node (ClassOrInterfaceDeclaration,
     *   EnumDeclaration, RecordDeclaration...). AnnotationDeclaration (@interface)
     *   chua duoc xu ly rieng o day - dang bi coi la "class", ban co the tach ra
     *   neu can phan biet.
     */
    private CodeChunkDto buildTypeChunk(TypeDeclaration<?> type, String source) {
        String kind = resolveKind(type);
        CodeChunkDto dto = new CodeChunkDto(kind, type.getNameAsString(),
                lineOf(type, true), lineOf(type, false), rangeText(type, source));

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nestedType) {
                // nested class/interface/enum/record -> de quy
                dto.addChild(buildTypeChunk(nestedType, source));
            } else if (member instanceof ConstructorDeclaration ctor) {
                dto.addChild(buildConstructorChunk(ctor, source));
            } else if (member instanceof MethodDeclaration method) {
                dto.addChild(buildMethodChunk(method, source));
            } else if (member instanceof FieldDeclaration field) {
                // CHU Y: 1 FieldDeclaration co the khai bao NHIEU bien tren 1 dong,
                // vi du: "private int a, b, c;" -> tach thanh nhieu chunk field rieng
                // de moi field co "name" ro rang thay vi gop chung "a, b, c".
                for (VariableDeclarator v : field.getVariables()) {
                    dto.addChild(buildFieldChunk(field, v, source));
                }
            }
            // CHU Y: cac loai member khac chua duoc xu ly rieng, dang bi bo qua
            // hoan toan (khong loi, chi khong tao chunk):
            //   - InitializerDeclaration (static { ... } hoac instance { ... } block)
            //   - AnnotationMemberDeclaration (thanh vien trong @interface)
            //   - EnumConstantDeclaration nam ngoai getMembers() cua EnumDeclaration
            //     thuc ra nam o EnumDeclaration.getEntries(), can xu ly rieng neu can
            //     tach tung hang so enum thanh 1 chunk.
        }

        // Vi du xu ly them enum constants (dang comment san, bo comment neu can dung):
        // if (type instanceof EnumDeclaration enumDecl) {
        //     enumDecl.getEntries().forEach(entry ->
        //         dto.addChild(new CodeChunkDto("enum-constant", entry.getNameAsString(),
        //                 lineOf(entry, true), lineOf(entry, false), rangeText(entry, source))));
        // }

        return dto;
    }

    private CodeChunkDto buildConstructorChunk(ConstructorDeclaration ctor, String source) {
        String name = ctor.getNameAsString() + "(" + paramsToString(ctor.getParameters().toString()) + ")";
        return new CodeChunkDto("constructor", name,
                lineOf(ctor, true), lineOf(ctor, false), rangeText(ctor, source));
    }

    private CodeChunkDto buildMethodChunk(MethodDeclaration method, String source) {
        String name = method.getNameAsString() + "(" + paramsToString(method.getParameters().toString()) + ")";
        CodeChunkDto dto = new CodeChunkDto("method", name,
                lineOf(method, true), lineOf(method, false), rangeText(method, source));
        dto.setType(method.getType().asString());
        return dto;
    }

    private CodeChunkDto buildFieldChunk(FieldDeclaration field, VariableDeclarator v, String source) {
        // CHU Y: dung Range cua ca FieldDeclaration (khong phai chi VariableDeclarator)
        // cho startLine/endLine khi field co annotation/modifier o dong rieng, de
        // "code" hien thi day du ca dong khai bao. Neu khai bao nhieu bien tren 1
        // dong ("int a, b;") thi ca 2 chunk se co chung 1 doan "code" (trung nhau) -
        // day la danh doi chap nhan duoc de doi lay su don gian; neu can tach code
        // rieng cho tung bien, phai tu ghep chuoi thay vi dung rangeText(field, ...).
        CodeChunkDto dto = new CodeChunkDto("field", v.getNameAsString(),
                lineOf(field, true), lineOf(field, false), rangeText(field, source));
        dto.setType(v.getType().asString());
        return dto;
    }

    // ---------- Helpers ----------

    private String resolveKind(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) return "enum";
        if (type instanceof RecordDeclaration) return "record";
        if (type.isInterface()) return "interface";
        return "class";
    }

    private String paramsToString(String raw) {
        // NodeList.toString() cua Parameters da tra ve dung dang "int a, String b";
        // ham nay chi la diem noi de sau nay muon format lai (vi du rut gon kieu
        // generic dai) thi sua 1 cho duy nhat.
        return raw;
    }

    /**
     * CHU Y QUAN TRONG: Node.getRange() tra ve Optional. No CHI rong (empty) neu
     * ban tu tao Node bang tay (khong parse tu source) - voi flow trong service nay
     * (luon parse tu source that) thi Range luon co mat, nhung van nen xu ly
     * Optional an toan thay vi goi .get() truc tiep de tranh crash neu sau nay co
     * ai tao Node thu cong o cho khac roi tai su dung ham nay.
     */
    private int lineOf(Node node, boolean begin) {
        return node.getRange()
                .map(r -> begin ? r.begin.line : r.end.line)
                .orElse(-1);
    }

    /**
     * Lay dung doan source code goc (tu ky tu dau den ky tu cuoi cua node) thay vi
     * dung node.toString() cua JavaParser - vi toString() se IN LAI code tu AST
     * (co the lam mat comment, doi format/indent so voi ban goc). Dung offset that
     * tren source giup "code" tra ve cho frontend giong 100% nhung gi nguoi dung go.
     *
     * CHU Y: can JavaParser luu Range o don vi "line/column", nen phai tu quy doi
     * sang offset ky tu bang cach dem qua tung dong. Neu file rat lon va goi ham
     * nay nhieu lan, nen precompute mang "vi tri bat dau moi dong" 1 LAN duy nhat
     * cho toan bo request thay vi tinh lai moi node (toi uu neu can, hien tai dang
     * tinh lai moi lan goi - chap nhan duoc voi file kich thuoc thong thuong).
     */
    private String rangeText(Node node, String source) {
        return node.getRange().map(r -> {
            int startOffset = offsetOfLineColumn(source, r.begin.line, r.begin.column);
            int endOffset = offsetOfLineColumn(source, r.end.line, r.end.column);
            if (startOffset < 0 || endOffset < 0 || endOffset < startOffset) {
                return node.toString(); // fallback an toan
            }
            return source.substring(startOffset, Math.min(endOffset + 1, source.length()));
        }).orElse(node.toString());
    }

    private int offsetOfLineColumn(String source, int line, int column) {
        int currentLine = 1;
        int i = 0;
        int n = source.length();
        while (i < n && currentLine < line) {
            if (source.charAt(i) == '\n') currentLine++;
            i++;
        }
        return i + (column - 1);
    }

    private void countChunks(List<CodeChunkDto> chunks, Map<String, Integer> stats) {
        for (CodeChunkDto c : chunks) {
            stats.merge(c.getKind(), 1, Integer::sum);
            if (!c.getChildren().isEmpty()) {
                countChunks(c.getChildren(), stats);
            }
        }
    }

    private String formatProblem(Problem p) {
        return p.getLocation()
                .map(loc -> "Dong " + loc.toRange().map(r -> r.begin.line).orElse(-1) + ": " + p.getMessage())
                .orElse(p.getMessage());
    }
}
