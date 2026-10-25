package com.leenex.exam.catalog.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportFileTest {

    @Test
    void csvHandlesQuotesCommasLineBreaksAndABom() throws Exception {
        String csv = "﻿a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\nlast,,\"\"";
        assertThat(ImportFile.readCsv(csv.getBytes(StandardCharsets.UTF_8))).containsExactly(
                List.of("a", "b", "c"),
                List.of("x, y", "say \"hi\"", "two\nlines"),
                List.of("last", "", ""));
    }

    @Test
    void anUnclosedQuoteIsAnError() {
        assertThatThrownBy(() -> ImportFile.readCsv("a,\"b".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ImportFile.FormatException.class);
    }

    @Test
    void templatesRoundTrip() throws Exception {
        List<ImportFile.Row> csv = ImportFile.read("t.csv", ImportFile.templateCsv());
        List<ImportFile.Row> xlsx = ImportFile.read("t.xlsx", ImportFile.templateXlsx());
        assertThat(csv).hasSize(1);
        assertThat(csv.getFirst().number()).isEqualTo(2);
        assertThat(csv.getFirst().get("correct")).isEqualTo("B");
        assertThat(xlsx.getFirst().cells()).isEqualTo(csv.getFirst().cells());
    }
}
