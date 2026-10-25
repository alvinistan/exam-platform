package com.leenex.exam.catalog.importing;

import com.leenex.exam.catalog.importing.ImportService.ImportReport;
import com.leenex.exam.catalog.question.Bank;
import com.leenex.exam.platform.security.CurrentUser;
import com.leenex.exam.platform.web.ApiException;
import com.leenex.exam.platform.web.ProblemCode;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Question imports from the approved CSV and XLSX templates (FR-QB-08). */
@RestController
@RequestMapping("/api/admin/catalog/question-imports")
@PreAuthorize("hasRole('ADMIN')")
public class ImportController {

    static final long MAX_BYTES = 5L * 1024 * 1024;

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportReport> upload(@RequestParam("file") MultipartFile file, @RequestParam UUID examId,
                                               @RequestParam(defaultValue = "MAIN") Bank bank) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Upload a file of up to 5 MB.");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(imports.upload(CurrentUser.id(), examId, bank, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/{importId}")
    public ImportReport get(@PathVariable UUID importId) {
        return imports.get(importId);
    }

    @PostMapping("/{importId}/confirm")
    public ImportReport confirm(@PathVariable UUID importId) {
        return imports.confirm(CurrentUser.id(), importId);
    }

    /** The template to fill in: a header row and one example question. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "csv") String format) {
        boolean xlsx = "xlsx".equals(format);
        if (!xlsx && !"csv".equals(format)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "format must be csv or xlsx.");
        }
        return ResponseEntity.ok()
                .contentType(xlsx
                        ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        : MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(xlsx ? "question-import-template.xlsx" : "question-import-template.csv").build().toString())
                .body(xlsx ? ImportFile.templateXlsx() : ImportFile.templateCsv());
    }
}
