package com.leenex.exam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/** CSV and XLSX imports: every row checked, nothing saved until confirmed (FR-QB-08). */
class QuestionImportTests extends CatalogTestSupport {

    private static final String HEADER = "subject,module,topic,type,question,option_a,option_b,option_c,option_d,option_e,"
            + "correct,explanation,key_points,definitions,difficulty,seconds,tags,marks";

    record Setup(UUID examId, String subject) {
    }

    /** An exam linked to a subject "Subject-…" with module "Module one" and topic "Topic one". */
    Setup exam() throws Exception {
        Content content = createContent();
        UUID exam = createExam(createCategory(), unique("import"), List.of(content.subjectId()));
        String subject = jdbc.queryForObject("SELECT title FROM catalog.subject WHERE id = ?", String.class,
                content.subjectId());
        return new Setup(exam, subject);
    }

    MockHttpServletResponse upload(UUID examId, String name, byte[] bytes, String bank) throws Exception {
        var request = multipart("/api/admin/catalog/question-imports")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .param("examId", examId.toString());
        if (bank != null) {
            request.param("bank", bank);
        }
        return mvc.perform(request.header("Authorization", "Bearer " + ADMIN)).andReturn().getResponse();
    }

    @Test
    void everyRowIsCheckedAndOnlyValidRowsAreCreatedOnConfirm() throws Exception {
        Setup s = exam();
        String csv = HEADER + "\r\n"
                + s.subject() + ",Module one,Topic one,SINGLE,\"Dose for 20 kg at 5 mg/kg, in mg?\",50,100,150,200,,B,"
                + "\"20 × 5 = 100\",Multiply|Check units,\"Dose: amount of drug\",simple,60,Dosage|calc,1\r\n"
                + s.subject() + ",Module one,No such topic,SINGLE,Q,a,b,c,d,,A,,,,SIMPLE,60,,\r\n"
                + s.subject() + ",Module one,Topic one,MULTI,\"Two lines\nof text\",a,b,,d,,A,,,,EASY,5,,\r\n"
                + ",,,,,,,,,,,,,,,,,\r\n";
        MockHttpServletResponse report = expect(upload(s.examId(), "questions.csv", csv.getBytes(StandardCharsets.UTF_8), null), 201);
        assertThat((Integer) read(report, "$.totalRows")).isEqualTo(3);
        assertThat((Integer) read(report, "$.validRows")).isEqualTo(1);
        assertThat((String) read(report, "$.bank")).isEqualTo("MAIN");
        assertThat((List<Integer>) read(report, "$.errors[?(@.row == 3)].row")).isNotEmpty();
        assertThat((List<String>) read(report, "$.errors[?(@.row == 3)].column")).containsExactly("topic");
        assertThat((List<String>) read(report, "$.errors[?(@.row == 4)].column"))
                .contains("option_d", "correct", "difficulty", "seconds");
        String importId = read(report, "$.importId");

        // Nothing is saved before confirmation.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_main.question_version WHERE stem_text LIKE 'Dose for 20 kg%'",
                Integer.class)).isZero();

        MockHttpServletResponse confirmed = expect(adminPost("/api/admin/catalog/question-imports/" + importId + "/confirm", null), 200);
        assertThat((String) read(confirmed, "$.status")).isEqualTo("CONFIRMED");
        List<String> created = read(confirmed, "$.createdQuestionIds");
        assertThat(created).hasSize(1);
        MockHttpServletResponse question = expect(adminGet("/api/admin/catalog/questions/" + created.getFirst()), 200);
        assertThat((String) read(question, "$.status")).isEqualTo("DRAFT");
        assertThat((String) read(question, "$.stemHtml")).isEqualTo("<p>Dose for 20 kg at 5 mg/kg, in mg?</p>");
        assertThat((List<Boolean>) read(question, "$.options[*].isCorrect")).containsExactly(false, true, false, false);
        assertThat((List<String>) read(question, "$.keyPoints")).containsExactly("Multiply", "Check units");
        assertThat((String) read(question, "$.definitions[0].term")).isEqualTo("Dose");
        assertThat((List<String>) read(question, "$.tags")).containsExactly("calc", "dosage");
        assertThat((Boolean) read(question, "$.validation.valid")).isTrue();

        assertThat((String) read(expect(adminPost("/api/admin/catalog/question-imports/" + importId + "/confirm", null), 409),
                "$.detail")).contains("already confirmed");
        assertThat(auditCount("QUESTION_IMPORT_CONFIRMED", importId)).isEqualTo(1);
    }

    @Test
    void xlsxFilesWorkTheSameWay() throws Exception {
        Setup s = exam();
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Questions");
            String[] header = HEADER.split(",");
            Object[] values = {s.subject(), "Module one", "Topic one", "multi", "Which are vitamins?", "C", "D", "Iron",
                    "Zinc", "", "A, B", "", "", "", "MEDIUM", 45, "", ""};
            var head = sheet.createRow(0);
            var row = sheet.createRow(1);
            for (int c = 0; c < header.length; c++) {
                head.createCell(c).setCellValue(header[c]);
                if (values[c] instanceof Integer i) {
                    row.createCell(c).setCellValue(i);
                } else {
                    row.createCell(c).setCellValue((String) values[c]);
                }
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        MockHttpServletResponse report = expect(upload(s.examId(), "Questions.XLSX", xlsx, "TRIAL"), 201);
        assertThat((List<?>) read(report, "$.errors")).isEmpty();
        assertThat((Integer) read(report, "$.validRows")).isEqualTo(1);
        List<String> created = read(expect(adminPost("/api/admin/catalog/question-imports/"
                + read(report, "$.importId") + "/confirm", null), 200), "$.createdQuestionIds");
        assertThat((String) read(adminGet("/api/admin/catalog/questions/" + created.getFirst()), "$.bank")).isEqualTo("TRIAL");
    }

    @Test
    void wrongFilesAreRefusedWithAReason() throws Exception {
        Setup s = exam();
        assertThat((String) read(expect(upload(s.examId(), "questions.txt", "x".getBytes(StandardCharsets.UTF_8), null), 400),
                "$.detail")).contains(".csv or .xlsx");
        assertThat((String) read(expect(upload(s.examId(), "q.csv", "subject,topic\r\nA,B\r\n".getBytes(StandardCharsets.UTF_8), null), 400),
                "$.detail")).startsWith("The header row is missing: module");
        assertThat((String) read(expect(upload(s.examId(), "q.csv", (HEADER + "\r\n\"never closed").getBytes(StandardCharsets.UTF_8), null), 400),
                "$.detail")).contains("never closed");
        assertThat((String) read(expect(upload(s.examId(), "q.xlsx", "not a workbook".getBytes(StandardCharsets.UTF_8), null), 400),
                "$.detail")).contains("readable .xlsx");
        assertThat((String) read(expect(upload(s.examId(), "q.csv", new byte[] {(byte) 0xff, (byte) 0xfe, 0x41}, null), 400),
                "$.detail")).contains("UTF-8");
        expect(upload(UUID.randomUUID(), "q.csv", HEADER.getBytes(StandardCharsets.UTF_8), null), 400);
    }

    @Test
    void importsExpireAndEmptyImportsCannotBeConfirmed() throws Exception {
        Setup s = exam();
        String bad = HEADER + "\r\n" + s.subject() + ",Module one,Topic one,SINGLE,Q,a,b,c,d,,Z,,,,SIMPLE,60,,\r\n";
        String empty = read(expect(upload(s.examId(), "q.csv", bad.getBytes(StandardCharsets.UTF_8), null), 201), "$.importId");
        assertThat((String) read(expect(adminPost("/api/admin/catalog/question-imports/" + empty + "/confirm", null), 409),
                "$.detail")).contains("No valid rows");

        String good = HEADER + "\r\n" + s.subject() + ",Module one,Topic one,SINGLE,Q,a,b,c,d,,A,,,,SIMPLE,60,,\r\n";
        String old = read(expect(upload(s.examId(), "q.csv", good.getBytes(StandardCharsets.UTF_8), null), 201), "$.importId");
        jdbc.update("UPDATE catalog.question_import SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", old);
        assertThat((String) read(expect(adminPost("/api/admin/catalog/question-imports/" + old + "/confirm", null), 409),
                "$.detail")).contains("expired");
    }

    @Test
    void templatesDownloadInBothFormats() throws Exception {
        MockHttpServletResponse csv = expect(adminGet("/api/admin/catalog/question-imports/template?format=csv"), 200);
        assertThat(csv.getHeader("Content-Disposition")).contains("question-import-template.csv");
        assertThat(new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8)).contains(HEADER);
        MockHttpServletResponse xlsx = expect(adminGet("/api/admin/catalog/question-imports/template?format=xlsx"), 200);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("subject");
        }
        expect(adminGet("/api/admin/catalog/question-imports/template?format=pdf"), 400);
    }
}
