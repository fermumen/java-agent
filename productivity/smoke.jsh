import java.io.ByteArrayInputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.Tika;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;
import org.knowm.xchart.XYChartBuilder;

void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
}

require("&lt;x&gt;".equals(StringEscapeUtils.escapeHtml4("<x>")), "Commons Text unavailable");
require(DigestUtils.sha256Hex("agent").length() == 64, "Commons Codec unavailable");
require(new DescriptiveStatistics(new double[] {1, 2, 3}).getMean() == 2.0, "Commons Math unavailable");
require(Jsoup.parse("<b>ready</b>").text().equals("ready"), "jsoup unavailable");
require(Parser.builder().build().parse("# ready") != null, "commonmark unavailable");
require(new Tika().detect(new ByteArrayInputStream("%PDF-1.7".getBytes())).equals("application/pdf"), "Tika unavailable");

try (var workbook = new XSSFWorkbook()) {
    workbook.createSheet("ready").createRow(0).createCell(0).setCellValue("ok");
    require(workbook.getNumberOfSheets() == 1, "POI unavailable");
}
try (var document = new PDDocument()) {
    require(document.getNumberOfPages() == 0, "PDFBox unavailable");
}

require(new XYChartBuilder().width(320).height(200).build() != null, "XChart unavailable");
Iterator<ImageReader> tiffReaders = ImageIO.getImageReadersByFormatName("TIFF");
boolean twelveMonkeys = false;
while (tiffReaders.hasNext()) {
    if (tiffReaders.next().getClass().getName().startsWith("com.twelvemonkeys.")) twelveMonkeys = true;
}
require(twelveMonkeys, "TwelveMonkeys ImageIO service metadata was not preserved");

System.out.println("productivity bundle smoke test passed");
/exit
