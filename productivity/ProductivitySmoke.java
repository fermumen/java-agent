import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.tika.Tika;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.BitmapEncoder.BitmapFormat;

/** Fail-closed deterministic checks for the shaded productivity bundle. */
public final class ProductivitySmoke {
    private static final String PASS_LINE = "productivity bundle smoke test passed";
    private static final String BSH_PASS_LINE = "beanshell smoke script passed";
    private static final String FAILURE_SENTINEL = "intentional productivity smoke failure sentinel";

    private ProductivitySmoke() { }

    public static void main(String[] args) throws Exception {
        require("&lt;x&gt;".equals(StringEscapeUtils.escapeHtml4("<x>")), "Commons Text unavailable");
        require(DigestUtils.sha256Hex("agent").length() == 64, "Commons Codec unavailable");
        require(new DescriptiveStatistics(new double[] {1, 2, 3}).getMean() == 2.0,
                "Commons Math unavailable");
        require(Jsoup.parse("<b>ready</b>").text().equals("ready"), "jsoup unavailable");
        require(Parser.builder().build().parse("# ready") != null, "commonmark unavailable");
        require(new ObjectMapper(new YAMLFactory()).readTree("value: 7").path("value").asInt() == 7,
                "Jackson YAML unavailable");
        require(new Tika().detect(new ByteArrayInputStream("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .equals("application/pdf"), "Tika unavailable");

        Path artifacts = Files.createTempDirectory("java-agent-productivity-smoke-");
        try {
            validateSpreadsheet(artifacts.resolve("roundtrip.xlsx"));
            validatePdf(artifacts.resolve("roundtrip.pdf"));
            validateDocx(artifacts.resolve("roundtrip.docx"));
            validatePptx(artifacts.resolve("roundtrip.pptx"));
            validateCsv(artifacts.resolve("roundtrip.csv"));
            validateChart(artifacts.resolve("chart.png"));
            validateImageIoServiceMetadata();
            validateBeanShellRunner(artifacts);
        } finally {
            deleteTree(artifacts);
        }
        System.out.println(PASS_LINE);
    }

    private static void validateSpreadsheet(Path file) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("sales");
            sheet.createRow(0).createCell(0).setCellValue(2);
            sheet.getRow(0).createCell(1).setCellValue(3);
            Cell total = sheet.createRow(1).createCell(0);
            total.setCellFormula("SUM(A1:B1)");
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            require(evaluator.evaluateFormulaCell(total) == CellType.NUMERIC, "formula evaluation failed");
            try (var output = Files.newOutputStream(file)) { workbook.write(output); }
        }
        try (InputStream input = Files.newInputStream(file); XSSFWorkbook reopened = new XSSFWorkbook(input)) {
            Cell total = reopened.getSheet("sales").getRow(1).getCell(0);
            require("SUM(A1:B1)".equals(total.getCellFormula()), "formula did not round-trip");
            require(Math.abs(reopened.getCreationHelper().createFormulaEvaluator()
                    .evaluate(total).getNumberValue() - 5.0) < 0.0001, "formula value did not round-trip");
        }
    }

    private static void validatePdf(Path file) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("java-agent productivity fixture");
                content.endText();
            }
            document.save(file.toFile());
        }
        try (PDDocument reopened = Loader.loadPDF(file.toFile())) {
            require(reopened.getNumberOfPages() == 1, "PDF page count did not round-trip");
            String extracted = new PDFTextStripper().getText(reopened);
            require(extracted.contains("java-agent productivity fixture"), "PDF text extraction failed");
            require(new Tika().detect(file).equals("application/pdf"), "Tika PDF detection failed");
        }
    }

    private static void validateDocx(Path file) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("docx productivity fixture");
            try (var output = Files.newOutputStream(file)) { document.write(output); }
        }
        try (InputStream input = Files.newInputStream(file); XWPFDocument reopened = new XWPFDocument(input)) {
            require(reopened.getParagraphs().size() == 1
                    && reopened.getParagraphs().get(0).getText().contains("docx productivity fixture"),
                    "DOCX text did not round-trip");
        }
    }

    private static void validatePptx(Path file) throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(new java.awt.geom.Rectangle2D.Double(20, 20, 300, 50));
            box.setText("pptx productivity fixture");
            try (var output = Files.newOutputStream(file)) { deck.write(output); }
        }
        try (InputStream input = Files.newInputStream(file); XMLSlideShow reopened = new XMLSlideShow(input)) {
            require(reopened.getSlides().size() == 1, "PPTX slide count did not round-trip");
            boolean foundText = false;
            for (var shape : reopened.getSlides().get(0).getShapes()) {
                if (shape instanceof XSLFTextShape
                        && ((XSLFTextShape) shape).getText().contains("pptx productivity fixture")) {
                    foundText = true;
                }
            }
            require(foundText, "PPTX text did not round-trip");
        }
    }

    private static void validateCsv(Path file) throws Exception {
        CSVFormat outputFormat = CSVFormat.DEFAULT.builder().setHeader("item", "value").build();
        try (Writer output = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(output, outputFormat)) {
            printer.printRecord("alpha", 5);
            printer.printRecord("beta, quoted", 8);
        }
        CSVFormat inputFormat = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = inputFormat.parse(input)) {
            var records = parser.getRecords();
            require(records.size() == 2, "CSV record count did not round-trip");
            require("alpha".equals(records.get(0).get("item")), "CSV field lookup failed");
            require("beta, quoted".equals(records.get(1).get("item")),
                    "CSV quoted field did not round-trip");
            require("8".equals(records.get(1).get("value")), "CSV value did not round-trip");
        }
    }

    private static void validateChart(Path file) throws Exception {
        XYChart chart = new XYChartBuilder().width(320).height(200).title("Fixture").build();
        chart.addSeries("values", Arrays.asList(1, 2, 3), Arrays.asList(2, 4, 3));
        BitmapEncoder.saveBitmap(chart, file.toString(), BitmapFormat.PNG);
        Path generated = Files.exists(file) ? file : Path.of(file.toString() + ".png");
        require(Files.isRegularFile(generated), "chart PNG file was not created");
        BufferedImage image = ImageIO.read(generated.toFile());
        require(image != null && image.getWidth() == 320 && image.getHeight() == 200,
                "chart PNG artifact did not round-trip");
    }

    private static void validateImageIoServiceMetadata() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("TIFF");
        boolean twelveMonkeys = false;
        while (readers.hasNext()) {
            if (readers.next().getClass().getName().startsWith("com.twelvemonkeys.")) twelveMonkeys = true;
        }
        require(twelveMonkeys, "TwelveMonkeys ImageIO service metadata was not preserved");
    }

    private static void validateBeanShellRunner(Path directory) throws Exception {
        Path workbook = directory.resolve("bsh-roundtrip.xlsx");
        Path passing = directory.resolve("passing.bsh");
        Files.writeString(passing, String.join("\n",
                "import java.io.*;",
                "import java.util.*;",
                "import org.apache.poi.xssf.usermodel.XSSFWorkbook;",
                "File file = new File(bsh.args[0]);",
                "XSSFWorkbook workbook = new XSSFWorkbook();",
                "try {",
                "    workbook.createSheet(\"data\").createRow(0).createCell(0).setCellValue(\"ready\");",
                "    FileOutputStream out = new FileOutputStream(file);",
                "    try { workbook.write(out); } finally { out.close(); }",
                "} finally { workbook.close(); }",
                "List values = new ArrayList();",
                "FileInputStream in = new FileInputStream(file);",
                "try {",
                "    XSSFWorkbook reopened = new XSSFWorkbook(in);",
                "    values.add(reopened.getSheet(\"data\").getRow(0).getCell(0).getStringCellValue());",
                "    reopened.close();",
                "} finally { in.close(); }",
                "if (!\"ready\".equals(values.get(0))) throw new IllegalStateException(\"workbook did not round-trip\");",
                "for (Object value : values) print(\"" + BSH_PASS_LINE + " \" + value);",
                ""), StandardCharsets.UTF_8);
        BeanShellRun ok = runBeanShell(passing, workbook.toString());
        require(ok.exit == 0, "passing BeanShell script failed: " + ok.output);
        require(ok.output.contains(BSH_PASS_LINE + " ready"), "BeanShell output missing: " + ok.output);
        require(Files.size(workbook) > 0, "BeanShell script did not write its workbook");

        Path bom = directory.resolve("bom.bsh");
        Files.writeString(bom, "\uFEFFprint(\"bom ok\");\n", StandardCharsets.UTF_8);
        BeanShellRun bomRun = runBeanShell(bom);
        require(bomRun.exit == 0 && bomRun.output.contains("bom ok"), "BOM script failed: " + bomRun.output);

        Path throwing = directory.resolve("throwing.bsh");
        Files.writeString(throwing, "print(\"before\");\nthrow new IllegalStateException(\"" + FAILURE_SENTINEL
                + "\");\nprint(\"" + BSH_PASS_LINE + "\");\n", StandardCharsets.UTF_8);
        BeanShellRun thrown = runBeanShell(throwing);
        require(thrown.exit != 0, "throwing BeanShell script exited successfully: " + thrown.output);
        require(thrown.output.contains(FAILURE_SENTINEL), "script exception was not reported: " + thrown.output);
        require(!thrown.output.contains(BSH_PASS_LINE), "script kept running after an exception");

        Path syntax = directory.resolve("syntax.bsh");
        Files.writeString(syntax, "int x = ;\n", StandardCharsets.UTF_8);
        require(runBeanShell(syntax).exit != 0, "BeanShell parse error exited successfully");

        Path undefined = directory.resolve("undefined.bsh");
        Files.writeString(undefined, "noSuchVariable.call();\n", StandardCharsets.UTF_8);
        require(runBeanShell(undefined).exit != 0, "BeanShell evaluation error exited successfully");

        require(runBeanShell(directory.resolve("missing.bsh")).exit != 0, "missing BeanShell script exited successfully");
    }

    private static BeanShellRun runBeanShell(Path script, String... scriptArgs) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Path log = Files.createTempFile("java-agent-productivity-smoke-bsh-", ".log");
        try {
            var command = new java.util.ArrayList<String>(Arrays.asList(java.toString(), "-jar",
                    System.getProperty("java.class.path"), script.toAbsolutePath().toString()));
            command.addAll(Arrays.asList(scriptArgs));
            Process child = new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!child.waitFor(60, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("BeanShell smoke child did not exit: " + script.getFileName());
            }
            return new BeanShellRun(child.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static final class BeanShellRun {
        final int exit;
        final String output;

        BeanShellRun(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException failed) { throw new UncheckedIOException(failed); }
            });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
