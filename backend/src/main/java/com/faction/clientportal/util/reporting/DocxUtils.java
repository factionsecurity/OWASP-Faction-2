package com.faction.clientportal.util.reporting;

import java.awt.Color;
import java.io.StringWriter;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.faction.clientportal.model.FieldType;
import org.docx4j.TextUtils;
import org.docx4j.TraversalUtil;
import org.docx4j.XmlUtils;
import org.docx4j.convert.in.xhtml.XHTMLImporterImpl;
import org.docx4j.jaxb.Context;
import org.docx4j.jaxb.XPathBinderAssociationIsPartialException;
import org.docx4j.model.datastorage.migration.VariablePrepare;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.relationships.RelationshipsPart;
import org.docx4j.toc.TocException;
import org.docx4j.toc.TocGenerator;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.Br;
import org.docx4j.wml.CTShd;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.Ftr;
import org.docx4j.wml.Hdr;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPrBase.Ind;
import org.docx4j.wml.R;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.RPr;
import org.docx4j.wml.RStyle;
import org.docx4j.wml.STTabTlc;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblGridCol;
import org.docx4j.wml.TblPr;
import org.docx4j.wml.TblWidth;
import org.docx4j.wml.Tc;
import org.docx4j.wml.TcPr;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.docx4j.wml.TrPr;
import org.docx4j.wml.CTHeight;
import org.docx4j.wml.CTTxbxContent;

import java.util.Objects;

import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.JAXBElement;

/**
 * Populates a DOCX template with assessment and vulnerability data.
 *
 * <p>Accepts a {@link ReportData} object so it has no dependency on Spring,
 * MongoDB, or any ORM.  Construct once per report and call {@link #generateDocx}.
 */
public class DocxUtils {

    public String FONT = "";

    private static final List<String> SEVERITIES =
            List.of("Critical", "High", "Medium", "Low", "Informational");

    private final WordprocessingMLPackage mlp;
    private final ReportData data;

    public DocxUtils(WordprocessingMLPackage mlp, ReportData data) {
        this.mlp = mlp;
        this.data = data;
    }

    // ── severity helpers ────────────────────────────────────────────────────

    /**
     * Maps a severity display string to the legacy integer used in
     * {@code ${riskCountN}} template variables (Critical=9 … Informational=5).
     */
    private int severityToInt(String severity) {
        if (severity == null) return -1;
        switch (severity.toUpperCase()) {
            case "CRITICAL":     return 9;
            case "HIGH":         return 8;
            case "MEDIUM":       return 7;
            case "LOW":          return 6;
            case "INFORMATIONAL": return 5;
            default:             return -1;
        }
    }

    private Date toDate(LocalDateTime ldt) {
        if (ldt == null) return null;
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    // ── vuln filtering (no section concept in new model) ───────────────────

    private List<ReportData.ReportVulnerability> getFilteredVulns() {
        List<ReportData.ReportVulnerability> vulns = data.getVulnerabilities();
        return vulns != null ? vulns : List.of();
    }

    // ── CDATA wrap ──────────────────────────────────────────────────────────

    private String CData(String text) {
        if (text == null) return "";
        return "<![CDATA[" + text + "]]>";
    }

    // ── imported-table normalisation ────────────────────────────────────────

    /**
     * Strips the browser-derived table geometry the XHTML importer emits, which only
     * ever causes trouble in Word.
     *
     * <p>The importer lays the HTML out with a CSS engine and then writes what it
     * measured into the DOCX. The damaging part is
     * {@code <w:trHeight w:val="390" w:hRule="atLeast"/>} on every row: a height
     * measured with the CSS engine's font metrics, which are not Word's. Word lays the
     * row out from its own metrics but honours the stored minimum, so the row rectangle
     * and the content rectangle disagree by a fraction of a pixel and Word's renderer
     * leaves a hairline of unpainted white along the bottom of the cell — invisible on a
     * white cell, obvious on a solid fill. Dragging the table re-measures the row and the
     * line goes away, which is the giveaway that the stored height, not the shading, is
     * wrong. Rows should size to their content; that is what the HTML meant. Neither
     * LibreOffice nor the PDF export shows the artifact, because both lay the table out
     * themselves rather than trusting the stored height.
     *
     * <p>A {@code <w:tblCellSpacing>} of zero (or of the {@code auto} type, where Word
     * picks the spacing itself) goes too. The HTML asked for {@code border-collapse:
     * collapse}; carrying the element anyway puts Word in the separated-cell model, where
     * each cell's fill is painted inside its own rectangle and the gaps between them are
     * page background. Spacing a table actually asked for is left alone.
     */
    private void normaliseImportedTables(List<Object> converted) {
        if (converted == null) return;
        for (Object o : converted) normaliseImportedTables(o);
    }

    /** Recurses itself rather than using getAllElementFromObject, which stops at the
     *  first match and so never reaches a table nested inside a cell. */
    private void normaliseImportedTables(Object node) {
        if (node instanceof JAXBElement) node = ((JAXBElement<?>) node).getValue();
        if (node == null) return;

        if (node instanceof Tbl) {
            TblPr tblPr = ((Tbl) node).getTblPr();
            if (tblPr != null && isMeaninglessCellSpacing(tblPr.getTblCellSpacing())) {
                tblPr.setTblCellSpacing(null);
            }
            clampToPageWidth((Tbl) node);
        }
        if (node instanceof Tc) {
            // A vertical cell margin under a fill is where Word paints its hairline of
            // unpainted white: it lays the content rectangle to one edge and the shading
            // to another and leaves a pixel between them. Invisible on a white cell, a
            // line down every row of a shaded table — the artifact reported on a pasted
            // table and again on every line of a code block, and gone from real reports
            // once this landed. Horizontal margins stay: the seam is only ever horizontal.
            TcPr tcPr = ((Tc) node).getTcPr();
            if (tcPr != null && tcPr.getShd() != null && tcPr.getTcMar() != null) {
                tcPr.getTcMar().setTop(null);
                tcPr.getTcMar().setBottom(null);
            }
        }
        if (node instanceof Tr) {
            TrPr trPr = ((Tr) node).getTrPr();
            if (trPr != null) {
                trPr.getCnfStyleOrDivIdOrGridBefore().removeIf(e ->
                        e instanceof JAXBElement
                        && ((JAXBElement<?>) e).getValue() instanceof CTHeight);
                if (trPr.getCnfStyleOrDivIdOrGridBefore().isEmpty()) ((Tr) node).setTrPr(null);
            }
        }
        if (node instanceof ContentAccessor) {
            for (Object child : ((ContentAccessor) node).getContent()) {
                normaliseImportedTables(child);
            }
        }
    }

    /** A hair of breathing room between an over-wide table and the right margin. */
    private static final int TABLE_RIGHT_GUTTER_TWIPS = 120;   // 8px

    /** How narrow a column may be squeezed while a table is being fitted to the page. */
    private static final int MIN_COLUMN_TWIPS = 600;           // 0.4 inch

    /**
     * Scales a table down when the importer measured it wider than the page can print.
     *
     * <p>The column widths are measured against the CSS engine's own viewport, which has
     * nothing to do with the page: a full-width table came out 13954 twips against a
     * printable width of 9027, so a third of it — and any long line inside it — sat off
     * the edge of the paper, visible nowhere. Columns are scaled in proportion, so the
     * table keeps its shape and only loses the overhang. A table that already fits is
     * left exactly as it is.
     */
    private void clampToPageWidth(Tbl tbl) {
        if (tbl.getTblGrid() == null || tbl.getTblGrid().getGridCol().isEmpty()) return;

        int available;
        try {
            available = mlp.getDocumentModel().getSections().get(0)
                    .getPageDimensions().getWritableWidthTwips() - TABLE_RIGHT_GUTTER_TWIPS;
        } catch (Exception e) {
            return;   // no section to measure against — leave the table alone
        }
        if (available <= 0) return;

        List<TblGridCol> cols = tbl.getTblGrid().getGridCol();
        int total = cols.stream().mapToInt(c -> c.getW().intValue()).sum();
        if (total <= available) return;

        // Taken out of the widest columns first, down to a floor, rather than scaled off
        // every column in proportion: a narrow column is narrow because its content is
        // (a gutter of line numbers, say), and shaving it just makes "200" wrap to
        // "20" / "0". The widest column is the one carrying the slack.
        int excess = total - available;
        int[] widths = cols.stream().mapToInt(c -> c.getW().intValue()).toArray();
        while (excess > 0) {
            int widest = 0;
            for (int i = 1; i < widths.length; i++) if (widths[i] > widths[widest]) widest = i;
            int reducible = widths[widest] - MIN_COLUMN_TWIPS;
            if (reducible <= 0) break;
            int cut = Math.min(excess, reducible);
            widths[widest] -= cut;
            excess -= cut;
        }
        // Every column already at the floor and it still does not fit — a table of many
        // narrow columns. Nothing left but to scale the lot.
        if (excess > 0) {
            double scale = (double) available / total;
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(1, (int) Math.round(widths[i] * scale));
            }
        }
        for (int i = 0; i < cols.size(); i++) cols.get(i).setW(BigInteger.valueOf(widths[i]));
        // the cells carry the same widths again in their own tcW
        for (Object r : getAllElementFromObject(tbl, Tr.class)) {
            int col = 0;
            for (Object cellObj : ((Tr) r).getContent()) {
                Object cell = cellObj instanceof JAXBElement
                        ? ((JAXBElement<?>) cellObj).getValue() : cellObj;
                if (!(cell instanceof Tc)) continue;
                TcPr pr = ((Tc) cell).getTcPr();
                if (pr != null && pr.getTcW() != null && col < cols.size()) {
                    pr.getTcW().setW(cols.get(col).getW());
                }
                col++;
            }
        }
    }

    private boolean isMeaninglessCellSpacing(TblWidth spacing) {
        if (spacing == null) return false;
        if (spacing.getType() == null || "auto".equalsIgnoreCase(spacing.getType())) return true;
        return spacing.getW() == null || spacing.getW().signum() == 0;
    }

    // ── table-width helpers ─────────────────────────────────────────────────

    private boolean cellContains(Tc cell, String variable) {
        for (Object obj : cell.getContent()) {
            String xml = XmlUtils.marshaltoString(obj, false, false);
            if (xml.contains(variable)) return true;
        }
        return false;
    }

    private Map<String, BigInteger> setWidths(Tc cell, String variable,
                                               Map<String, BigInteger> widths) {
        if (cellContains(cell, "${" + variable + "}")) {
            if (cell.getTcPr() != null && cell.getTcPr().getTcW() != null) {
                BigInteger margin = BigInteger.valueOf(200);
                widths.put(variable,
                        cell.getTcPr().getTcW().getW().subtract(margin));
            } else {
                widths.put(variable, BigInteger.valueOf(-1));
            }
        }
        return widths;
    }

    // ── vuln-table processing ────────────────────────────────────────────────

    private void checkTables(String variable, String customCSS)
            throws JAXBException, Docx4JException {

        List<ReportData.ReportVulnerability> filteredVulns = getFilteredVulns();
        List<Object> tables = getAllElementFromObject(mlp.getMainDocumentPart(), Tbl.class);

        for (Object table : tables) {
            List<Object> paragraphs = getAllElementFromObject(table, P.class);
            List<Object> cells      = getAllElementFromObject(table, Tc.class);
            Map<String, BigInteger> widths = new HashMap<>();
            for (Object cell : cells) {
                Tc tc = (Tc) cell;
                widths = setWidths(tc, "desc",    widths);
                widths = setWidths(tc, "rec",     widths);
                widths = setWidths(tc, "details", widths);
            }

            String tableVariable = "${" + variable + "}";
            String txt = getMatchingText(paragraphs, tableVariable);
            if (txt == null) continue;

            HashMap<String, String> colorMap       = new HashMap<>();
            HashMap<String, String> cellMap        = new HashMap<>();
            HashMap<String, String> customFieldMap = new HashMap<>();

            String colors = getMatchingText(paragraphs, "${color");
            if (colors != null) {
                colors = colors.replace("${color", "").replace("}", "").trim();
                for (String pair : colors.split(",")) {
                    pair = pair.trim();
                    String[] kv = pair.split("=");
                    if (kv.length == 2) colorMap.put(kv[0].trim(), kv[1].trim().toUpperCase());
                }
            }
            colors = getMatchingText(paragraphs, "${cells");
            if (colors != null) {
                colors = colors.replace("${cells", "").replace("}", "").trim();
                for (String pair : colors.split(",")) {
                    pair = pair.trim();
                    String[] kv = pair.split("=");
                    if (kv.length == 2) cellMap.put(kv[0].trim(), kv[1].trim().toUpperCase());
                }
            }
            String customFields = getMatchingText(paragraphs, "${custom-fields");
            if (customFields != null) {
                customFields = customFields.replace("${custom-fields", "").replace("}", "").trim();
                for (String pair : customFields.split(",")) {
                    pair = pair.trim();
                    String[] kv = pair.split("=");
                    if (kv.length == 2) customFieldMap.put(kv[0].trim(), kv[1].trim().toUpperCase());
                }
            }
            String noIssuesText = getMatchingText(paragraphs, "${noIssuesText");
            if (noIssuesText != null) {
                noIssuesText = noIssuesText.replace("${noIssuesText ", "").replace("}", "");
            } else {
                noIssuesText = "No issues detected for this section.";
            }

            int index   = indexOfRow((Tbl) table, paragraphs, "${loop");
            String loop = getMatchingText(paragraphs, "${loop");
            int rowsPlus = 0;
            if (loop != null && loop.contains("-")) {
                loop = loop.split("\\-")[1];
                loop = loop.split("\\}")[0];
                rowsPlus = Integer.parseInt(loop);
            }
            if (index == -1) continue;

            List<String> xmls = new LinkedList<>();
            for (int i = 0; i <= rowsPlus; i++) {
                Tr row = (Tr) ((Tbl) table).getContent().get(index + i);
                xmls.add(XmlUtils.marshaltoString(row, false, false));
            }
            for (int i = rowsPlus; i >= 0; i--) {
                ((Tbl) table).getContent().remove(index);
            }

            SimpleDateFormat formatter = new SimpleDateFormat("MM/dd/yyyy");
            int count  = 1;
            int sevIndex = 0;
            String prevSev = "";

            for (ReportData.ReportVulnerability v : filteredVulns) {
                String sev = v.getSeverity() == null ? "" : v.getSeverity();
                if (sev.equals(prevSev)) {
                    sevIndex++;
                } else {
                    prevSev  = sev;
                    sevIndex = 1;
                }

                for (String xml : xmls) {
                    String nxml = xml.replaceAll("\\$\\{vulnName\\}",        CData(v.getName()));
                    nxml = nxml.replaceAll("\\$\\{severity\\}",   CData(sev));
                    nxml = nxml.replaceAll("\\$\\{impact\\}",     CData(v.getImpact() == null ? "" : v.getImpact()));
                    nxml = nxml.replaceAll("\\$\\{cvssScore\\}",  CData(v.getCvssScoreStr()));
                    nxml = nxml.replaceAll("\\$\\{cvssString\\}", CData(v.getCvssString() == null ? "" : v.getCvssString()));
                    nxml = nxml.replaceAll("\\$\\{tracking\\}",   CData(v.getTrackingId() == null ? "" : v.getTrackingId()));

                    Date opened = toDate(v.getOpenedAt());
                    nxml = nxml.replaceAll("\\$\\{openedAt\\}",
                            opened != null ? formatter.format(opened) : "");
                    Date closed = toDate(v.getClosedAt());
                    nxml = nxml.replaceAll("\\$\\{closedAt\\}",
                            closed != null ? formatter.format(closed) : "");
                    Date devClosed = toDate(v.getClosedInDevAt());
                    nxml = nxml.replaceAll("\\$\\{closedInDevAt\\}",
                            devClosed != null ? formatter.format(devClosed) : "");
                    Date stagingClosed = toDate(v.getClosedInStagingAt());
                    nxml = nxml.replaceAll("\\$\\{closedInStagingAt\\}",
                            stagingClosed != null ? formatter.format(stagingClosed) : "");

                    try {
                        nxml = nxml.replaceAll("\\$\\{vid\\}", "" + v.getId());
                    } catch (Exception ignored) {}

                    nxml = nxml.replaceAll("\\$\\{likelihood\\}",
                            v.getLikelihood() == null ? "" : v.getLikelihood());
                    nxml = nxml.replaceAll("\\$\\{category\\}",
                            v.getCategoryName() == null ? "UnCategorized" : CData(v.getCategoryName()));
                    nxml = nxml.replaceAll("\\$\\{remediationStatus\\}",
                            v.isOpen() ? "Open" : "Closed");
                    nxml = nxml.replaceAll("\\$\\{count\\}", "" + count);
                    nxml = nxml.replaceAll("\\$\\{loop\\}", "");
                    nxml = nxml.replaceAll("\\$\\{loop\\-[0-9]+\\}", "");

                    if (!sev.isEmpty()) {
                        nxml = nxml.replaceAll("\\$\\{sevId\\}",
                                sev.charAt(0) + "V" + sevIndex);
                    } else {
                        nxml = nxml.replaceAll("\\$\\{sevId\\}", "V" + sevIndex);
                    }

                    // plain-text UDF replacements
                    if (v.getFieldTypes() != null) {
                        for (Map.Entry<String, FieldType> entry : v.getFieldTypes().entrySet()) {
                            String varName  = entry.getKey();
                            FieldType fType = entry.getValue();
                            String value    = v.getFieldValue(varName);
                            if (fType != FieldType.RICH_TEXT) {
                                nxml = nxml.replaceAll(
                                        "\\$\\{" + Pattern.quote(varName) + "\\}",
                                        CData(value));
                                // colour/cell mappings driven by ${custom-fields} config
                                if (customFieldMap.containsKey(varName)
                                        && colorMap.containsKey(value)) {
                                    String colorMatch = customFieldMap.get(varName);
                                    String color      = colorMap.get(value);
                                    if ((colorMatch != null && !colorMatch.isEmpty())
                                            && (color != null && !color.isEmpty())) {
                                        nxml = nxml.replaceAll(
                                                "w:val=\"" + colorMatch + "\"",
                                                "w:val=\"" + color + "\"");
                                    }
                                }
                                if (customFieldMap.containsKey(varName)
                                        && cellMap.containsKey(value)) {
                                    String colorMatch = customFieldMap.get(varName);
                                    String color      = cellMap.get(value);
                                    if ((colorMatch != null && !colorMatch.isEmpty())
                                            && (color != null && !color.isEmpty())) {
                                        nxml = nxml.replaceAll(
                                                "w:fill=\"" + colorMatch + "\"",
                                                "w:fill=\"" + color + "\"");
                                    }
                                }
                            }
                        }
                    }

                    // sentinel-colour replacements
                    nxml = nxml.replaceAll("w:color=\"FAC701\"",
                            "w:color=\"" + colorMap.getOrDefault(sev, "000000") + "\"");
                    nxml = nxml.replaceAll("w:color=\"FAC702\"",
                            "w:color=\"" + colorMap.getOrDefault(v.getLikelihood(), "000000") + "\"");
                    nxml = nxml.replaceAll("w:color=\"FAC703\"",
                            "w:color=\"" + colorMap.getOrDefault(v.getImpact(), "000000") + "\"");
                    nxml = nxml.replaceAll("w:fill=\"FAC701\"",
                            "w:fill=\"" + cellMap.getOrDefault(sev, "FFFFFF") + "\"");
                    nxml = nxml.replaceAll("w:fill=\"FAC702\"",
                            "w:fill=\"" + cellMap.getOrDefault(v.getLikelihood(), "FFFFFF") + "\"");
                    nxml = nxml.replaceAll("w:fill=\"FAC703\"",
                            "w:fill=\"" + cellMap.getOrDefault(v.getImpact(), "FFFFFF") + "\"");
                    nxml = nxml.replaceAll("w:val=\"FAC701\"",
                            "w:val=\"" + colorMap.getOrDefault(sev, "000000") + "\"");
                    nxml = nxml.replaceAll("w:val=\"FAC702\"",
                            "w:val=\"" + colorMap.getOrDefault(v.getLikelihood(), "000000") + "\"");
                    nxml = nxml.replaceAll("w:val=\"FAC703\"",
                            "w:val=\"" + colorMap.getOrDefault(v.getImpact(), "000000") + "\"");

                    Tr newrow = (Tr) XmlUtils.unmarshalString(nxml);

                    // hyperlink UDF replacements
                    if (v.getFieldTypes() != null) {
                        for (String varName : v.getFieldTypes().keySet()) {
                            replaceHyperlink(newrow,
                                    "${" + varName + " link}",
                                    v.getFieldValue(varName));
                        }
                    }
                    replaceHyperlink(newrow, "${cvssString link}",
                            v.getCvssString() == null ? "" : v.getCvssString());

                    ((Tbl) table).getContent().add(newrow);

                    HashMap<String, List<Object>> map2 = new HashMap<>();
                    if (xml.contains("${rec}")) {
                        String rec = v.getRecommendation() != null ? v.getRecommendation() : "";
                        rec = replaceVulnUdfsInHtml(rec, v);
                        rec = replaceFigureVariables(rec, count);
                        map2.put("${rec}", wrapHTML(rec, customCSS, "rec"));
                    }
                    if (xml.contains("${desc}")) {
                        String desc = v.getDescription() != null ? v.getDescription() : "";
                        desc = replaceVulnUdfsInHtml(desc, v);
                        desc = replaceFigureVariables(desc, count);
                        map2.put("${desc}", wrapHTML(desc, customCSS, "desc"));
                    }
                    if (xml.contains("${details}")) {
                        String details = v.getDetails() != null ? v.getDetails() : "";
                        details = replaceVulnUdfsInHtml(details, v);
                        details = replaceFigureVariables(details, count);
                        map2.put("${details}", wrapHTML(details, customCSS, "details"));
                    }

                    // rich-text UDF fields
                    if (v.getFieldTypes() != null) {
                        for (Map.Entry<String, FieldType> entry : v.getFieldTypes().entrySet()) {
                            if (entry.getValue() == FieldType.RICH_TEXT) {
                                String varName = entry.getKey();
                                map2.put("${" + varName + "}",
                                        wrapHTML(v.getFieldValue(varName), customCSS, varName));
                            }
                        }
                    }

                    replaceHTML(table, map2);
                }
                count++;
            }

            if (filteredVulns.isEmpty()) {
                ObjectFactory factory = Context.getWmlObjectFactory();
                Tr newrow = factory.createTr();
                Tc td     = factory.createTc();
                P  p      = factory.createP();
                R  r      = factory.createR();
                Text text = factory.createText();
                text.setValue(noIssuesText);
                r.getContent().add(text);
                p.getContent().add(r);
                td.getContent().add(p);
                newrow.getContent().add(td);
                ((Tbl) table).getContent().add(newrow);
            }

            int tmpIndex;
            while ((tmpIndex = indexOfRow((Tbl) table, paragraphs, "${")) != -1) {
                ((Tbl) table).getContent().remove(tmpIndex);
            }
        }
    }

    // ── main generate ────────────────────────────────────────────────────────

    /**
     * Resolves a template placeholder that no built-in variable claimed — the
     * {@code ${faction-bar-chart}} an App Store extension is expected to fill in.
     *
     * <p>Kept as a plain function so this class stays free of Spring and of the
     * extension machinery: the report generator supplies one backed by the
     * installed {@code ReportManager} extensions.
     */
    @FunctionalInterface
    public interface TokenResolver {
        /**
         * @param token the full placeholder including braces, e.g. {@code ${faction-bar-chart}}
         * @return replacement HTML, or null / the token unchanged when nothing handled it
         */
        String resolve(String token);
    }

    /**
     * A paragraph consisting of nothing but one placeholder.
     *
     * <p>The body deliberately allows spaces and punctuation: extension placeholders
     * take arguments. The checklist extension is driven by
     * {@code ${checklist-owasp-top-10 columns=[Question,Status,Comment]}}, which a
     * whitespace-free pattern would never see. Only {@code }} is excluded, so the
     * match still ends at the first closing brace and two placeholders on one line
     * do not collapse into a single spurious match.
     */
    private static final Pattern LONE_TOKEN = Pattern.compile("\\$\\{([^}]+)\\}");

    public WordprocessingMLPackage generateDocx(String customCSS) throws Exception {
        return generateDocx(customCSS, null);
    }

    public WordprocessingMLPackage generateDocx(String customCSS, TokenResolver tokenResolver)
            throws Exception {

        VariablePrepare.prepare(mlp);

        checkTables("vulnTable", customCSS);
        setFindings(customCSS);

        // Replace ${summary1} / ${summary2} with HTML from matching UDFs (if present)
        HashMap<String, List<Object>> summaryMap = new HashMap<>();
        summaryMap.put("${summary1}",
                wrapHTML(data.getFieldValue("summary1"), customCSS, "summary1"));
        summaryMap.put("${summary2}",
                wrapHTML(data.getFieldValue("summary2"), customCSS, "summary2"));
        replaceHTML(mlp.getMainDocumentPart(), summaryMap, false);

        replaceAssessment(customCSS);

        // Last, so extensions are only offered placeholders Faction itself did not claim.
        replaceExtensionTokens(customCSS, tokenResolver);

    	tocGenerator(mlp);

        return mlp;
    }

    /**
     * Offers every unclaimed {@code ${...}} placeholder to the extension chain and
     * swaps in whatever comes back, converted from HTML.
     *
     * <p>Only a paragraph that consists of nothing but the placeholder is eligible.
     * That is the same rule {@code ${summary1}} and the rich-text UDFs follow, and
     * it is what makes returning a block element — the {@code <img>} of a chart —
     * meaningful. A placeholder sitting mid-sentence has no sensible block
     * replacement, so it is left alone rather than silently mangled.
     *
     * <p>Runs after every built-in substitution: anything still bracketed at this
     * point is by definition something Faction has no variable for, which is
     * exactly the set an extension should be asked about.
     */
    private void replaceExtensionTokens(String customCSS, TokenResolver tokenResolver)
            throws Docx4JException {
        if (tokenResolver == null) return;

        Map<String, List<Object>> replacements = new HashMap<>();
        for (P paragraph : getParagraphs(mlp.getMainDocumentPart())) {
            StringWriter paragraphText = new StringWriter();
            try {
                TextUtils.extractText(paragraph, paragraphText);
            } catch (Exception ignored) {
                continue;
            }
            String token = paragraphText.toString().trim();
            if (token.isEmpty() || replacements.containsKey(token)) continue;

            Matcher tokenMatch = LONE_TOKEN.matcher(token);
            if (!tokenMatch.matches()) continue;

            String resolved = tokenResolver.resolve(token);
            if (resolved == null || resolved.equals(token)) continue;

            replacements.put(token, wrapHTML(resolved, customCSS, cssClassFor(tokenMatch.group(1))));
        }

        // once=false: the same placeholder may legitimately appear more than once,
        // for instance inside an expanded per-finding block.
        if (!replacements.isEmpty()) {
            replaceHTML(mlp.getMainDocumentPart(), replacements, false);
        }
    }

    /**
     * Turns a placeholder body into a CSS class the template can style, e.g.
     * {@code checklist-owasp-top-10 columns=[…]} becomes {@code checklist-owasp-top-10}.
     * Only the leading name is used — the arguments belong to the extension, not to
     * the stylesheet.
     */
    private String cssClassFor(String tokenBody) {
        String name = tokenBody.trim().split("\\s+", 2)[0];
        return name.replaceAll("[^A-Za-z0-9_-]", "");
    }

    // ── findings-block processing ────────────────────────────────────────────

    private void setFindings(String customCSS) throws JAXBException, Docx4JException {
        int begin = getIndex(mlp.getMainDocumentPart(), "${fiBegin}");
        int end   = getIndex(mlp.getMainDocumentPart(), "${fiEnd}");
        if (begin == -1 || end == -1) return;

        HashMap<String, String> colorMap       = new HashMap<>();
        HashMap<String, String> cellMap        = new HashMap<>();
        HashMap<String, String> customFieldMap = new HashMap<>();
        String noIssuesText = "No issues detected for this section.";

        List<Object> findingTemplate = new LinkedList<>();
        for (int i = begin; i <= end; i++) {
            Object node = mlp.getMainDocumentPart().getContent().get(i);
            findingTemplate.add(node);
            List<Object> paragraphs = getAllElementFromObject(node, P.class);

            String colors = getMatchingText(paragraphs, "${color");
            if (colors != null) {
                colors = colors.replace("${color", "").replace("}", "").trim();
                for (String pair : colors.split(",")) {
                    pair = pair.trim();
                    String[] kv = pair.split("=");
                    if (kv.length == 2) colorMap.put(kv[0].trim(), kv[1].trim().toUpperCase());
                }
            }
            colors = getMatchingText(paragraphs, "${fill");
            if (colors != null) {
                colors = colors.replace("${fill", "").replace("}", "").trim();
                for (String pair : colors.split(",")) {
                    pair = pair.trim();
                    String[] kv = pair.split("=");
                    if (kv.length == 2) cellMap.put(kv[0].trim(), kv[1].trim().toUpperCase());
                }
            }
            String customFields = getMatchingText(paragraphs, "${custom-fields");
            if (customFields != null) {
                customFields = customFields.replace("${custom-fields", "").replace("}", "").trim();
                for (String pair : customFields.split(",")) {
                    pair = pair.trim();
                    String[] kv = pair.split("=");
                    if (kv.length == 2) customFieldMap.put(kv[0].trim(), kv[1].trim().toUpperCase());
                }
            }
            String nit = getMatchingText(paragraphs, "${noIssuesText");
            if (nit != null) {
                noIssuesText = nit.replace("${noIssuesText ", "").replace("}", "");
            }
        }

        for (int i = end; i >= begin; i--) {
            mlp.getMainDocumentPart().getContent().remove(i);
        }

        SimpleDateFormat formatter = new SimpleDateFormat("MM/dd/yyyy");
        List<ReportData.ReportVulnerability> filteredVulns = getFilteredVulns();

        if (filteredVulns.isEmpty()) {
            ObjectFactory factory = Context.getWmlObjectFactory();
            P p = factory.createP();
            R r = factory.createR();
            Text text = factory.createText();
            text.setValue(noIssuesText);
            r.getContent().add(text);
            p.getContent().add(r);
            mlp.getMainDocumentPart().getContent().add(begin++, p);
            return;
        }

        int index  = 0;
        String prevSev = "";

        for (ReportData.ReportVulnerability v : filteredVulns) {
            String sev = v.getSeverity() == null ? "" : v.getSeverity();
            if (sev.equals(prevSev)) {
                index++;
            } else {
                prevSev = sev;
                index   = 1;
            }

            for (Object obj : findingTemplate) {
                String xml = XmlUtils.marshaltoString(obj, false, false);
                if (xml.isEmpty()) continue;

                String nxml = xml.replaceAll("\\$\\{vulnName\\}",  CData(v.getName()));
                nxml = nxml.replaceAll("\\$\\{severity\\}",  CData(sev));
                nxml = nxml.replaceAll("\\$\\{impact\\}",
                        v.getImpact() == null ? "" : v.getImpact());
                nxml = nxml.replaceAll("\\$\\{cvssString\\}",
                        v.getCvssString() == null ? "" : v.getCvssString());
                nxml = nxml.replaceAll("\\$\\{cvssScore\\}",  v.getCvssScoreStr());
                nxml = nxml.replaceAll("\\$\\{tracking\\}",
                        v.getTrackingId() == null ? "" : v.getTrackingId());

                Date opened = toDate(v.getOpenedAt());
                nxml = nxml.replaceAll("\\$\\{openedAt\\}",
                        opened != null ? formatter.format(opened) : "");
                Date closed = toDate(v.getClosedAt());
                nxml = nxml.replaceAll("\\$\\{closedAt\\}",
                        closed != null ? formatter.format(closed) : "");
                Date devClosed = toDate(v.getClosedInDevAt());
                nxml = nxml.replaceAll("\\$\\{closedInDevAt\\}",
                        devClosed != null ? formatter.format(devClosed) : "");
                Date stagingClosed = toDate(v.getClosedInStagingAt());
                nxml = nxml.replaceAll("\\$\\{closedInStagingAt\\}",
                        stagingClosed != null ? formatter.format(stagingClosed) : "");

                try {
                    nxml = nxml.replaceAll("\\$\\{vid\\}", "" + v.getId());
                } catch (Exception ignored) {}

                nxml = nxml.replaceAll("\\$\\{likelihood\\}",
                        v.getLikelihood() == null ? "" : v.getLikelihood());
                nxml = nxml.replaceAll("\\$\\{category\\}",
                        v.getCategoryName() == null ? "UnCategorized" : CData(v.getCategoryName()));
                nxml = nxml.replaceAll("\\$\\{remediationStatus\\}",
                        v.isOpen() ? "Open" : "Closed");
                if (!sev.isEmpty()) {
                    nxml = nxml.replaceAll("\\$\\{sevId\\}", sev.charAt(0) + "V" + index);
                } else {
                    nxml = nxml.replaceAll("\\$\\{sevId\\}", "V" + index);
                }

                // strip config rows
                if (nxml.contains("${color") || nxml.contains("${fill")
                        || nxml.contains("${custom-fields")) {
                    nxml = "";
                }

                if (v.getFieldTypes() != null && !nxml.isEmpty()) {
                    for (Map.Entry<String, FieldType> entry : v.getFieldTypes().entrySet()) {
                        String varName  = entry.getKey();
                        FieldType fType = entry.getValue();
                        String value    = v.getFieldValue(varName);
                        if (fType != FieldType.RICH_TEXT) {
                            nxml = nxml.replaceAll(
                                    "\\$\\{" + Pattern.quote(varName) + "\\}",
                                    CData(value));
                            if (customFieldMap.containsKey(varName)
                                    && colorMap.containsKey(value)) {
                                String cm = customFieldMap.get(varName);
                                String co = colorMap.get(value);
                                if ((cm != null && !cm.isEmpty()) && (co != null && !co.isEmpty())) {
                                    nxml = nxml.replaceAll(
                                            "w:val=\"" + cm + "\"",
                                            "w:val=\"" + co + "\"");
                                }
                            }
                            if (customFieldMap.containsKey(varName)
                                    && cellMap.containsKey(value)) {
                                String cm = customFieldMap.get(varName);
                                String co = cellMap.get(value);
                                if ((cm != null && !cm.isEmpty()) && (co != null && !co.isEmpty())) {
                                    nxml = nxml.replaceAll(
                                            "w:fill=\"" + cm + "\"",
                                            "w:fill=\"" + co + "\"");
                                }
                            }
                        }
                    }
                }

                nxml = nxml.replaceAll("w:val=\"FAC701\"",
                        "w:val=\"" + colorMap.getOrDefault(sev, "000000") + "\"");
                nxml = nxml.replaceAll("w:val=\"FAC702\"",
                        "w:val=\"" + colorMap.getOrDefault(v.getLikelihood(), "000000") + "\"");
                nxml = nxml.replaceAll("w:val=\"FAC703\"",
                        "w:val=\"" + colorMap.getOrDefault(v.getImpact(), "000000") + "\"");
                nxml = nxml.replaceAll("w:fill=\"FAC701\"",
                        "w:fill=\"" + cellMap.getOrDefault(sev, "FFFFFF") + "\"");
                nxml = nxml.replaceAll("w:fill=\"FAC702\"",
                        "w:fill=\"" + cellMap.getOrDefault(v.getLikelihood(), "FFFFFF") + "\"");
                nxml = nxml.replaceAll("w:fill=\"FAC703\"",
                        "w:fill=\"" + cellMap.getOrDefault(v.getImpact(), "FFFFFF") + "\"");

                if (!nxml.isEmpty()) {
                    try {
                        Object paragraph = XmlUtils.unmarshalString(nxml);
                        if (v.getFieldTypes() != null) {
                            for (String varName : v.getFieldTypes().keySet()) {
                                replaceHyperlink(paragraph,
                                        "${" + varName + " link}",
                                        v.getFieldValue(varName));
                            }
                        }
                        replaceHyperlink(paragraph, "${cvssString link}",
                                v.getCvssString() == null ? "" : v.getCvssString());
                        mlp.getMainDocumentPart().getContent().add(begin++, paragraph);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }

            // Add description / recommendation / details below the findings block
            int count = filteredVulns.indexOf(v) + 1;
            HashMap<String, List<Object>> map2 = new HashMap<>();

            String desc = v.getDescription() != null ? v.getDescription() : "";
            desc = replaceVulnUdfsInHtml(desc, v);
            desc = replaceFigureVariables(desc, count);
            map2.put("${desc}", wrapHTML(desc, customCSS, "desc"));

            String rec = v.getRecommendation() != null ? v.getRecommendation() : "";
            rec = replaceVulnUdfsInHtml(rec, v);
            rec = replaceFigureVariables(rec, count);
            map2.put("${rec}", wrapHTML(rec, customCSS, "rec"));

            String details = v.getDetails() != null ? v.getDetails() : "";
            details = replaceVulnUdfsInHtml(details, v);
            details = replaceFigureVariables(details, count);
            map2.put("${details}", wrapHTML(details, customCSS, "details"));

            // rich-text UDF placeholders in the findings block
            if (v.getFieldTypes() != null) {
                for (Map.Entry<String, FieldType> entry : v.getFieldTypes().entrySet()) {
                    if (entry.getValue() == FieldType.RICH_TEXT) {
                        String varName = entry.getKey();
                        map2.put("${" + varName + "}",
                                wrapHTML(v.getFieldValue(varName), customCSS, varName));
                    }
                }
            }

            replaceHTML(mlp.getMainDocumentPart(), map2, true);
        }
    }

    // ── assessment-level replacement ─────────────────────────────────────────

    private void replaceAssessment(String customCSS) throws Exception {
        SimpleDateFormat formatter = new SimpleDateFormat("MM/dd/yyyy");

        String assessorsNl      = "";
        String assessorsComma   = "";
        String assessorsBullets = "<ul>";
        boolean isFirst = true;

        List<ReportData.ReportUser> assessors = data.getAssessors();
        if (assessors == null) assessors = List.of();

        for (ReportData.ReportUser u : assessors) {
            String name = u.getFullName();
            assessorsNl      += name + "<br/>";
            assessorsComma   += (isFirst ? "" : ", ") + name;
            assessorsBullets += "<li class='bullets'>" + name + "</li>";
            isFirst = false;
        }
        assessorsBullets += "</ul>";

        String firstAssessorName  = assessors.isEmpty() ? "" : assessors.get(0).getFullName();
        String firstAssessorEmail = assessors.isEmpty() ? "" : (assessors.get(0).getEmail() == null ? "" : assessors.get(0).getEmail());

        HashMap<String, String> map = new HashMap<>();
        map.put(getKey("asmtname"),             data.getAssessmentName() == null ? "" : data.getAssessmentName());
        map.put(getKey("asmtid"),               data.getAssessmentId()   == null ? "" : data.getAssessmentId());
        map.put(getKey("asmtappid"),            data.getApplicationId()  == null ? "" : data.getApplicationId());
        map.put(getKey("asmtassessor"),         firstAssessorName);
        map.put(getKey("asmtassessor_email"),   firstAssessorEmail);
        map.put(getKey("asmtassessors_comma"),  assessorsComma);
        map.put(getKey("remediation"),          data.getRemediationManagerName() == null ? "" : data.getRemediationManagerName());
        map.put(getKey("asmtteam"),             "");   // no team concept in new model
        map.put(getKey("asmttype"),             data.getAssessmentTypeName() == null ? "" : data.getAssessmentTypeName());
        map.put(getKey("asmtaccesskey"),        data.getAssessmentId()   == null ? "" : data.getAssessmentId());
        map.put(getKey("totalopenvulns"),       getTotalOpenVulns());
        map.put(getKey("totalclosedvulns"),     getTotalClosedVulns());
        map.putAll(getVulnMap());

        // assessment-level plain-text UDFs
        if (data.getFieldTypes() != null) {
            for (Map.Entry<String, FieldType> entry : data.getFieldTypes().entrySet()) {
                if (entry.getValue() != FieldType.RICH_TEXT) {
                    map.put("" + entry.getKey(), data.getFieldValue(entry.getKey()));
                }
            }
        }

        replacementHyperlinks(mlp.getMainDocumentPart(), map);
        replacementDate("today",     new Date());
        replacementDate("asmtStart", toDate(data.getStartDate()));
        replacementDate("asmtEnd",   toDate(data.getEndDate()));
        replacementText(map);

        // assessment-level rich-text UDFs
        Map<String, List<Object>> cfMap = new HashMap<>();
        if (data.getFieldTypes() != null) {
            for (Map.Entry<String, FieldType> entry : data.getFieldTypes().entrySet()) {
                if (entry.getValue() == FieldType.RICH_TEXT) {
                    String varName = entry.getKey();
                    cfMap.put("${" + varName + "}",
                            wrapHTML(data.getFieldValue(varName), customCSS, varName));
                }
            }
        }

        Map<String, List<Object>> map2 = new HashMap<>();
        map2.put("${asmtAssessors_Lines}",  wrapHTML(assessorsNl,      customCSS, ""));
        map2.put("${asmtAssessors_Bullets}", wrapHTML(assessorsBullets, customCSS, ""));
        map2.put("${asmtAssessors_Comma}",  wrapHTML(assessorsComma,    customCSS, ""));
        replaceHTML(mlp.getMainDocumentPart(), map2);
        replaceHTML(mlp.getMainDocumentPart(), cfMap, false);
        replaceHeaderAndFooter(map);
    }

    // ── vuln count helpers ───────────────────────────────────────────────────

    private Map<String, String> getVulnMap() {
        int[] results = new int[10];
        int totals    = 0;
        for (ReportData.ReportVulnerability v : getFilteredVulns()) {
            int i = severityToInt(v.getSeverity());
            if (i >= 0) {
                results[i]++;
                totals++;
            }
        }
        Map<String, String> maps = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            maps.put("riskCount" + i, "" + results[i]);
        }
        maps.put("riskTotal", "" + totals);
        return maps;
    }

    private String getVulnCount(String content) {
        int[] results = new int[10];
        int totals    = 0;
        for (ReportData.ReportVulnerability v : getFilteredVulns()) {
            int i = severityToInt(v.getSeverity());
            if (i >= 0) {
                results[i]++;
                totals++;
            }
        }
        for (int i = 0; i < 10; i++) {
            content = content.replaceAll("\\$\\{riskCount" + i + "\\}", "" + results[i]);
        }
        content = content.replaceAll("\\$\\{riskTotal\\}", "" + totals);
        return content;
    }

    private String getTotalOpenVulns() {
        return "" + getFilteredVulns().stream().filter(ReportData.ReportVulnerability::isOpen).count();
    }

    private String getTotalClosedVulns() {
        return "" + getFilteredVulns().stream().filter(v -> !v.isOpen()).count();
    }

    // ── severity loop replacement (for ${[asmtCRITICAL]} style vars) ────────

    private String loopReplace(String content) {
        for (String sev : SEVERITIES) {
            content = innerLoop(content, sev);
        }
        return content;
    }

    private String innerLoop(String content, String severity) {
        String var = severity.toUpperCase();
        if (!content.contains("{[asmt" + var + "]}")) return content;

        String html        = "<ol>\r\n";
        boolean isSomething = false;
        for (ReportData.ReportVulnerability v : getFilteredVulns()) {
            if (severity.equalsIgnoreCase(v.getSeverity())) {
                isSomething = true;
                html += "<li>" + v.getName() + "</li>";
            }
        }
        html += "</ol>";
        if (!isSomething) {
            html = "<i>No vulnerabilities found at this severity.</i>&nbsp;";
        }
        content = content.replaceAll("\\{\\[asmt" + var + "\\]\\}", html);
        return content;
    }

    // ── inline-image replacement ─────────────────────────────────────────────

    /**
     * Replaces {@code /api/v1/inline-images/{id}/serve} src attributes with
     * base-64 data URIs using the bytes loaded by the service.
     */
    private String replaceImageLinks(String text) {
        //text = centerImages(text);

        Map<String, byte[]>   bytes        = data.getInlineImageBytes();
        Map<String, String>   contentTypes = data.getInlineImageContentTypes();
        if (bytes == null || bytes.isEmpty()) return injectImageDimensions(text);

        // Matches /api/v1/inline-images/{id} — the URL returned by InlineImageService
        // and stored verbatim in <img src> by the editor. No /serve suffix.
        Pattern pattern = Pattern.compile(
                "/api/v1/inline-images/([^/\"'\\s>]+)");
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String imageId      = matcher.group(1);
            byte[] imageBytes   = bytes.get(imageId);
            String contentType  = contentTypes != null
                    ? contentTypes.getOrDefault(imageId, "image/png")
                    : "image/png";
            if (imageBytes != null) {
                String b64 = Base64.getEncoder().encodeToString(imageBytes);
                matcher.appendReplacement(sb,
                        Matcher.quoteReplacement("data:" + contentType + ";base64," + b64));
            } else {
                matcher.appendReplacement(sb, matcher.group(0));
            }
        }
        matcher.appendTail(sb);
        return injectImageDimensions(sb.toString());
    }

    /** Cap applied to auto-sized images, matching {@link #IMAGE_MAX_WIDTH_TWIPS}. */
    private static final int IMAGE_MAX_WIDTH_PX = 600;

    private static final Pattern IMG_TAG =
            Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_DATA_SRC =
            Pattern.compile("src\\s*=\\s*\"data:[^;\"]+;base64,([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /**
     * Adds explicit pixel width/height attributes to <img> tags that don't
     * already carry them. docx4j sizes images without DPI metadata at 72dpi —
     * 4/3 larger than the 96dpi the editor displays at — so a pasted
     * screenshot rendered ~33% too large. Explicit pixel attributes make the
     * importer lay the image out at the size the editor shows; anything wider
     * than {@link #IMAGE_MAX_WIDTH_PX} is scaled down proportionally to fit.
     * Images the user explicitly sized (existing width/height) are untouched.
     */
    private String injectImageDimensions(String html) {
        Matcher m = IMG_TAG.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            String replaced = tag;
            boolean hasExplicitSize = tag.matches("(?is).*\\s(?:width|height)\\s*=.*");
            if (!hasExplicitSize) {
                int[] dims = readDataUriDimensions(tag);
                if (dims != null) {
                    int w = dims[0], h = dims[1];
                    if (w > IMAGE_MAX_WIDTH_PX) {
                        h = Math.round(h * (float) IMAGE_MAX_WIDTH_PX / w);
                        w = IMAGE_MAX_WIDTH_PX;
                    }
                    String suffix = tag.endsWith("/>") ? "/>" : ">";
                    replaced = tag.substring(0, tag.length() - suffix.length())
                            + " width=\"" + w + "\" height=\"" + h + "\"" + suffix;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Decodes a data-URI img src just far enough to read its pixel dimensions. */
    private int[] readDataUriDimensions(String imgTag) {
        Matcher src = IMG_DATA_SRC.matcher(imgTag);
        if (!src.find()) return null;
        try {
            byte[] imageBytes = Base64.getDecoder().decode(src.group(1));
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(imageBytes));
            if (img == null) return null;
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            return null; // undecodable image — let the importer size it
        }
    }

    private String centerImages(String content) {
        int index = content.indexOf("<img ");
        while (index != -1) {
            String first  = content.substring(0, index);
            String second = content.substring(index);
            second  = second.replaceFirst("/>", "></img>");
            content = first + second;
            index   = content.indexOf("<img ", index + 1);
        }
        content = content.replaceAll("<p><img",         "<center><img");
        content = content.replaceAll("</img><br /></p>", "</img></center>");
        return content;
    }

    // ── XHTML sanitization ────────────────────────────────────────────────────

    private static final org.owasp.html.PolicyFactory REPORT_HTML_POLICY =
        new org.owasp.html.HtmlPolicyBuilder()
            // ── Block / structural elements ──────────────────────────────────
            .allowElements("p", "div", "br", "hr",
                           "h1", "h2", "h3", "h4", "h5", "h6",
                           "ul", "ol", "li",
                           "pre", "blockquote",
                           "table", "thead", "tbody", "tfoot", "tr", "th", "td",
                           "colgroup", "col",
                           "center", "figure", "figcaption")
            // ── Inline formatting elements ────────────────────────────────────
            .allowElements("span", "b", "strong", "i", "em", "u", "s",
                           "strike", "sub", "sup", "code", "mark")
            // ── Links — https / http / mailto only; no javascript: ───────────
            .allowElements("a")
            .allowUrlProtocols("https", "http", "mailto")
            .allowAttributes("href", "title", "target").onElements("a")
            .requireRelNofollowOnLinks()
            // ── Images — data URIs (inline screenshots) + remote src ─────────
            .allowElements("img")
            .allowUrlProtocols("https", "http", "data")
            .allowAttributes("src", "alt", "width", "height", "style").onElements("img")
            // ── Table attributes ──────────────────────────────────────────────
            .allowAttributes("colspan", "rowspan", "align", "valign",
                             "border", "cellpadding", "cellspacing", "width").onElements(
                                 "table", "tr", "th", "td")
            // ── Inline CSS (style attribute) — limited safe properties only ──
            // Allows font/color/text/spacing properties; blocks position, z-index, etc.
            //
            // Values are parenthesis-free apart from the colour functions named below.
            // That exception is load-bearing: a colour set through the browser's CSSOM
            // (the table editor's cell-background menu, execCommand) serialises as
            // `rgb(37, 99, 235)`, and because this pattern has to match the attribute in
            // full, one such value used to drop the entire style attribute — the fill
            // vanished from the report while a pasted hex one survived. Only the four
            // colour functions are admitted, and no parenthesis may appear inside them,
            // so `url(...)` and `expression(...)` stay out.
            .allowAttributes("style").matching(
                java.util.regex.Pattern.compile(
                    "(?:(?:font-(?:size|family|weight|style)|color|background-color|"
                    + "text-(?:align|decoration|indent)|line-height|letter-spacing|"
                    + "white-space|word-break|margin(?:-(?:top|right|bottom|left))?|"
                    + "padding(?:-(?:top|right|bottom|left))?|"
                    + "border(?:-(?:top|right|bottom|left))?(?:-(?:width|style|color))?|"
                    + "width|height|vertical-align|list-style(?:-type)?)"
                    + "\\s*:\\s*(?:[^;\"'<>()]|(?:rgba?|hsla?)\\([\\w%.,\\s/+-]*\\))+;?\\s*)*",
                    java.util.regex.Pattern.CASE_INSENSITIVE))
            .onElements("span", "p", "div", "td", "th", "h1", "h2", "h3",
                        "h4", "h5", "h6", "pre", "li", "img")
            // ── Common global attributes ──────────────────────────────────────
            .allowAttributes("class").globally()
            .toFactory();

    // Unique token used to round-trip newlines through the OWASP HTML parser,
    // which normalizes bare \n characters as whitespace during parsing.
    private static final String NEWLINE_TOKEN = "FACTIONNLTOKEN";

    // Formatting whitespace between table/list structural tags. Once newlines
    // are tokenized they become real text nodes, and text directly inside
    // <table>/<tr> fragments the XHTML importer's table into one single-cell
    // table per cell, while text directly inside <ul>/<ol> becomes an EMPTY
    // extra bullet before every item — so strip it before tokenizing.
    private static final java.util.regex.Pattern STRUCTURE_WHITESPACE =
        java.util.regex.Pattern.compile(
            "(?i)(<(?:table|thead|tbody|tfoot|tr|colgroup|ul|ol)\\b[^>]*>"
            + "|</(?:thead|tbody|tfoot|tr|td|th|colgroup|li)>)\\s+(?=<)");

    /**
     * 1. Strips XSS vectors (script tags, event handlers, javascript: URLs, etc.)
     *    using the OWASP Java HTML Sanitizer allowlist policy.
     * 2. Converts the resulting safe HTML to well-formed XHTML required by
     *    docx4j's XML-based importer:
     *      - Named HTML entities (&amp;nbsp; etc.) → numeric equivalents
     *      - Bare &amp; in text → &amp;amp;
     *      - Void elements (&lt;br&gt;, &lt;hr&gt;, &lt;img&gt;) → self-closed
     */
    private static String sanitizeForXhtml(String html) {
        if (html == null) return "";

        // Step 0: Remove formatting whitespace between table/list structural
        // tags so it can't become text nodes that break table layout or add
        // empty bullets (see STRUCTURE_WHITESPACE).
        html = STRUCTURE_WHITESPACE.matcher(html).replaceAll("$1");

        // Step 1: Protect newlines before the OWASP HTML parser normalizes them.
        // The parser treats bare \n as whitespace and collapses/strips them from
        // text nodes (notably inside <pre> blocks). Encode to a token, sanitize,
        // then restore — the token is pure ASCII alphanumeric so it survives intact.
        html = html.replace("\n", NEWLINE_TOKEN);

        // Step 2: XSS sanitization via OWASP allowlist
        html = REPORT_HTML_POLICY.sanitize(html);

        // Step 3: Restore newlines
        html = html.replace(NEWLINE_TOKEN, "\n");

        // Step 4: Named HTML entities → numeric XML equivalents
        html = html.replace("&nbsp;",   "&#160;")
                   .replace("&ndash;",  "&#8211;")
                   .replace("&mdash;",  "&#8212;")
                   .replace("&ldquo;",  "&#8220;")
                   .replace("&rdquo;",  "&#8221;")
                   .replace("&lsquo;",  "&#8216;")
                   .replace("&rsquo;",  "&#8217;")
                   .replace("&hellip;", "&#8230;")
                   .replace("&laquo;",  "&#171;")
                   .replace("&raquo;",  "&#187;")
                   .replace("&copy;",   "&#169;")
                   .replace("&reg;",    "&#174;")
                   .replace("&trade;",  "&#8482;")
                   .replace("&euro;",   "&#8364;")
                   .replace("&pound;",  "&#163;")
                   .replace("&yen;",    "&#165;")
                   .replace("&cent;",   "&#162;")
                   .replace("&times;",  "&#215;")
                   .replace("&divide;", "&#247;")
                   .replace("&deg;",    "&#176;")
                   .replace("&plusmn;", "&#177;")
                   .replace("&frac12;", "&#189;")
                   .replace("&frac14;", "&#188;")
                   .replace("&frac34;", "&#190;");

        // Step 5: Escape any remaining bare & not part of a valid entity reference
        html = html.replaceAll("&(?![a-zA-Z0-9#][a-zA-Z0-9]*;)", "&amp;");

        // Step 6: Self-close void elements (XML requires this; HTML5 does not)
        html = html.replaceAll("(?i)<br\\s*/?>",                    "<br />");
        html = html.replaceAll("(?i)<hr\\s*/?>",                    "<hr />");
        html = html.replaceAll("(?i)<img([^>]*)(?<!/)>",             "<img$1 />");
        html = html.replace("<p><br /></p>","<p></p>");

        // Step 7: docx4j's XHTML importer does not honour <pre> whitespace
        // semantics — convert newlines inside <pre> blocks to <br /> so line
        // breaks are preserved in the generated document.
        html = convertPreNewlinesToBr(html);

        // Step 8: Center images and figures.
        html = centerImagesAndFigures(html);

        return html;
    }

    /**
     * Centers images in the DOCX output:
     * <ul>
     *   <li>Adds {@code text-align:center} to {@code <figure>} elements so
     *       captioned images are block-centered.</li>
     *   <li>Adds {@code text-align:center} to {@code <p>} elements whose first
     *       content is an {@code <img>} (the common editor output for pasted /
     *       uploaded images not wrapped in a figure).</li>
     * </ul>
     * Uses plain string replacement (not regex) after OWASP sanitization, which
     * guarantees lowercase tag names — no catastrophic-backtracking risk.
     */
    private static String centerImagesAndFigures(String html) {
        // <figure> with no existing attributes
        html = html.replace("<figure>", "<figure style=\"text-align:center\">");

        // <figure> with existing attributes — inject style before the closing >
        // Only replaces if there is no style attribute already present.
        if (html.contains("<figure ") && !html.contains("<figure style=")) {
            html = html.replaceAll("<figure ([^>]*)>",
                    "<figure style=\"text-align:center\" $1>");
        }

        // <p> whose first child is an <img>: make it centered.
        // Covers both <p><img  and  <p> <img  (space after p tag close).
        html = html.replace("<p><img ",     "<p style=\"text-align:center\"><img ");
        html = html.replace("<p> <img ",    "<p style=\"text-align:center\"><img ");
        html = html.replace("<p>\n<img ",   "<p style=\"text-align:center\"><img ");

        return html;
    }

    /**
     * Finds each &lt;pre&gt;...&lt;/pre&gt; block and replaces bare newlines
     * in its body with &lt;br /&gt; so docx4j renders them as line breaks.
     */
    private static String convertPreNewlinesToBr(String html) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        String lower = html.toLowerCase();
        while (pos < html.length()) {
            int preStart = lower.indexOf("<pre", pos);
            if (preStart == -1) {
                sb.append(html, pos, html.length());
                break;
            }
            int tagEnd = html.indexOf(">", preStart);
            if (tagEnd == -1) {
                sb.append(html, pos, html.length());
                break;
            }
            int preEnd = lower.indexOf("</pre>", tagEnd);
            if (preEnd == -1) {
                sb.append(html, pos, html.length());
                break;
            }
            // Text before this <pre> block
            sb.append(html, pos, tagEnd + 1);
            // Content of the <pre> block with newlines → <br />
            sb.append(html.substring(tagEnd + 1, preEnd).replace("\n", "<br />"));
            sb.append("</pre>");
            pos = preEnd + 6;
        }
        return sb.toString();
    }

    // ── HTML replacement (for rich-text fields) ──────────────────────────────

    /**
     * Images render at their natural size unless wider than this, in which
     * case they are scaled down (aspect ratio preserved) to fit. 600px at
     * 96dpi = 9000 twips. The flying-saucer renderer inside the XHTML
     * importer ignores CSS max-width on images, so this must be set here.
     */
    private static final int IMAGE_MAX_WIDTH_TWIPS = 600 * 15;

    private List<Object> wrapHTML(String content, String customCSS, String className)
            throws Docx4JException {
        XHTMLImporterImpl xhtml = new XHTMLImporterImpl(mlp);
        xhtml.setMaxWidth(IMAGE_MAX_WIDTH_TWIPS, null);
        RFonts rfonts = Context.getWmlObjectFactory().createRFonts();
        rfonts.setAscii(this.FONT);
        XHTMLImporterImpl.addFontMapping("Arial", rfonts);
        XHTMLImporterImpl.addFontMapping("arial", rfonts);
        if (className == null) className = "";

        content = replacement(content);
        content = sanitizeForXhtml(content);

        List<Object> converted = xhtml.convert(
                "<!DOCTYPE html><html><head>"
                + buildStyleBlock(customCSS)
                + "</head><body><div class='" + className + "'>"
                + content + "</div></body></html>",
                null);
        normaliseImportedTables(converted);
        return converted;
    }

    /**
     * Built-in styles + template CSS for rich-text conversion. The
     * {@code .rte-table} rules give WYSIWYG tables visible borders in the
     * generated DOCX (the editor styles them in-app only); they come before
     * the template CSS so templates can override them.
     */
    private String buildStyleBlock(String customCSS) {
        String fontRule = (this.FONT == null || this.FONT.isBlank())
                ? "" : "font-family:" + this.FONT + ";";
        return "<style>html{padding:0;margin:0;margin-right:0px;}\r\n"
                + "body{padding:0;margin:0;" + fontRule + "}\r\n"
                // Deterministic paragraph spacing so DOCX paragraphs read as
                // paragraphs; templates can override via their custom CSS.
                + "p{margin:0 0 10px 0;}\r\n"
                + "li p,td p,th p{margin:0;}\r\n"
                + ".rte-table{border-collapse:collapse;}\r\n"
                + ".rte-table td,.rte-table th{border:1px solid #999999;padding:4px;}\r\n"
                // Code blocks arrive from the editor as a table — a gutter of line
                // numbers beside the code, or the code alone for a plain fence. Dracula
                // Official (#282a36 panel, #f8f8f2 code, #6272a4 numbers, #44475a
                // divider), so it reads as a code panel with no template CSS at all; a
                // template overrides any of these classes, its own CSS coming after.
                // max-width:none — a template's "table{max-width:480px}" would otherwise
                // squeeze the panel and wrap the code.
                + ".code-block{border-collapse:collapse;width:100%;max-width:none;margin:10px 0;}\r\n"
                // The font has to be named on the cells, not just on the table: the stock
                // template CSS carries "td,th{font-family:Arial}", and a rule aimed at the
                // cell beats a font merely inherited from the table — which is why the
                // code printed proportional.
                + ".code-block,.code-block td{"
                + "font-family:Consolas,'Courier New',monospace;font-size:9pt;}\r\n"
                // Shading stays on the cells: the importer does not map a table's own
                // background-color to w:tblPr/w:shd at all, so a table-level fill lands
                // as no fill.
                + ".code-block td{border:none;padding:1px 8px;vertical-align:top;"
                + "background-color:#282a36;color:#f8f8f2;}\r\n"
                // Breathing room at the top and bottom of the panel. Word ignores a
                // table's own margin — the only space it honours around a table comes
                // from the neighbouring paragraphs, which a template can zero out — so
                // the padding is a short shaded row inside the panel. Not cell padding:
                // that becomes a cell margin, and a cell margin under a fill is exactly
                // where Word paints its hairline of white.
                + ".code-block tr.code-block-pad td{font-size:5pt;}\r\n"
                // td-qualified: ".code-block td" above would otherwise outrank a bare
                // ".code-block-gutter" and paint the numbers in the code colour.
                + ".code-block td.code-block-gutter{text-align:right;color:#6272a4;"
                + "border-right:1px solid #44475a;width:1%;white-space:nowrap;}\r\n"
                + ".code-block td.code-block-line{width:99%;word-break:break-all;}\r\n"
                + customCSS + "</style>";
    }

    private List<Object> wrapHTML(String value, String customCSS,
                                   String className, BigInteger maxWidth)
            throws Docx4JException {
        XHTMLImporterImpl xhtml = new XHTMLImporterImpl(mlp);
        xhtml.setMaxWidth(IMAGE_MAX_WIDTH_TWIPS, null);
        RFonts rfonts = Context.getWmlObjectFactory().createRFonts();
        rfonts.setAscii(this.FONT);
        XHTMLImporterImpl.addFontMapping("Arial", rfonts);
        XHTMLImporterImpl.addFontMapping("arial", rfonts);

        try {
            value = replaceImageLinks(value); // convert API URLs → data: URIs before OWASP sanitizer runs
            value = sanitizeForXhtml(value);

            List<Object> converted = xhtml.convert(
                    "<!DOCTYPE html><html><head>"
                    + buildStyleBlock(customCSS)
                    + "</head><body><div class='" + className + "'>"
                    + value + "</div></body></html>",
                    null);
            normaliseImportedTables(converted);

            for (Object o : converted) {
                if (o instanceof P) {
                    P p = (P) o;
                    if (p.getPPr() != null && p.getPPr().getShd() != null
                            && p.getPPr().getPBdr() != null
                            && p.getPPr().getInd() != null) {
                        Ind indent = p.getPPr().getInd();
                        indent.setRight(indent.getLeft());
                        p.getPPr().setInd(indent);
                    }
                }
                if (maxWidth.intValue() > -1 && o instanceof Tbl) {
                    Tbl t = (Tbl) o;
                    if (t.getTblPr() != null && t.getTblPr().getTblW() != null) {
                        t.getTblPr().getTblW().setType("dxa");
                        t.getTblPr().getTblW().setW(maxWidth);
                    }
                }
            }
            return converted;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    // ── content replacement (for HTML-embedded variables) ────────────────────

    private String replacement(String content) {
        List<ReportData.ReportUser> assessors = data.getAssessors();
        if (assessors == null) assessors = List.of();

        String assessorsNl      = "";
        String assessorsComma   = "";
        String assessorsBullets = "<ul>";
        boolean isFirst = true;
        for (ReportData.ReportUser u : assessors) {
            String name = u.getFullName();
            assessorsNl      += name + "<br/>";
            assessorsComma   += (isFirst ? "" : ", ") + name;
            assessorsBullets += "<li class='bullets'>" + name + "</li>";
            isFirst = false;
        }
        assessorsBullets += "</ul>";

        String firstAssessorName  = assessors.isEmpty() ? "" : assessors.get(0).getFullName();
        String firstAssessorEmail = assessors.isEmpty() ? "" :
                (assessors.get(0).getEmail() == null ? "" : assessors.get(0).getEmail());

        content = content.replaceAll("\\$\\{asmtName\\}",
                data.getAssessmentName() == null ? "" : data.getAssessmentName());
        content = content.replaceAll("\\$\\{asmtId\\}",
                data.getAssessmentId() == null ? "" : data.getAssessmentId());
        content = content.replaceAll("\\$\\{asmtAppId\\}",
                data.getApplicationId() == null ? "" : data.getApplicationId());
        content = content.replaceAll("\\$\\{asmtAssessor\\}", firstAssessorName);
        content = content.replaceAll("\\$\\{asmtAssessor_Email\\}", firstAssessorEmail);
        content = content.replaceAll("\\$\\{asmtAssessors_Lines\\}",  assessorsNl);
        content = content.replaceAll("\\$\\{asmtAssessors_Comma\\}",  assessorsComma);
        content = content.replaceAll("\\$\\{asmtAssessors_Bullets\\}", assessorsBullets);
        content = content.replaceAll("\\$\\{remediation\\}",
                data.getRemediationManagerName() == null ? "" : data.getRemediationManagerName());
        content = content.replaceAll("\\$\\{asmtTeam\\}", "");
        content = content.replaceAll("\\$\\{asmtType\\}",
                data.getAssessmentTypeName() == null ? "" : data.getAssessmentTypeName());
        content = replaceDateVariable(content, "today",     new Date());
        content = replaceDateVariable(content, "asmtStart", toDate(data.getStartDate()));
        content = replaceDateVariable(content, "asmtEnd",   toDate(data.getEndDate()));
        content = content.replaceAll("\\$\\{asmtAccessKey\\}",
                data.getAssessmentId() == null ? "" : data.getAssessmentId());
        content = content.replaceAll("\\$\\{totalOpenVulns\\}",  getTotalOpenVulns());
        content = content.replaceAll("\\$\\{totalClosedVulns\\}", getTotalClosedVulns());
        content = getVulnCount(content);

        // assessment-level UDFs in HTML content (plain text only)
        if (data.getFieldTypes() != null) {
            for (Map.Entry<String, FieldType> entry : data.getFieldTypes().entrySet()) {
                if (entry.getValue() != FieldType.RICH_TEXT) {
                    String varName = entry.getKey();
                    content = content.replaceAll(
                            "\\$\\{" + Pattern.quote(varName) + "\\}",
                            data.getFieldValue(varName));
                }
            }
        }

        content = replaceImageLinks(content); // convert API URLs → data: URIs before OWASP sanitizer runs
        content = loopReplace(content);
        return content;
    }

    // ── UDF replacement inside vuln HTML fields ──────────────────────────────

    private String replaceVulnUdfsInHtml(String content,
                                          ReportData.ReportVulnerability v) {
        if (v.getFieldTypes() == null) return content;
        for (Map.Entry<String, FieldType> entry : v.getFieldTypes().entrySet()) {
            String varName  = entry.getKey();
            FieldType fType = entry.getValue();
            if (fType != FieldType.RICH_TEXT) {
                try {
                    content = content.replaceAll(
                            "\\$\\{" + Pattern.quote(varName) + "\\}",
                            v.getFieldValue(varName));
                } catch (Exception ignored) {}
            }
        }
        return content;
    }

    // ── date-variable replacement ────────────────────────────────────────────

    private void replacementDate(String key, Date date)
            throws JAXBException, Docx4JException {
        if (date == null) return;
        String xml = XmlUtils.marshaltoString(
                mlp.getMainDocumentPart().getContents(), false, false);
        xml = replaceDateVariable(xml, key, date);
        mlp.getMainDocumentPart().setContents(
                (org.docx4j.wml.Document) XmlUtils.unmarshalString(xml));
    }

    public static String replaceDateVariable(String text, String key, Date date) {
        if (date == null || key == null) return text;
        String patternStr = "\\$\\{\\s*" + Pattern.quote(key)
                + "(?:\\s+([^}]+))?\\s*\\}";
        Pattern pattern = Pattern.compile(patternStr);
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String dateFormat = matcher.group(1);
            if (dateFormat == null || dateFormat.trim().isEmpty()) {
                dateFormat = "MM/dd/yyyy";
            } else {
                dateFormat = dateFormat.trim();
            }
            try {
                String formatted = new SimpleDateFormat(dateFormat).format(date);
                matcher.appendReplacement(result, Matcher.quoteReplacement(formatted));
            } catch (Exception e) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // ── text / hyperlink replacement ─────────────────────────────────────────

    private void replacementHyperlinks(Object document, Map<String, String> map) {
        for (String key : map.keySet()) {
            replaceHyperlink(document, "${" + key + " link}", map.get(key));
        }
    }

    private void replacementText(Map<String, String> map)
            throws JAXBException, Docx4JException {
        String xml = XmlUtils.marshaltoString(
                mlp.getMainDocumentPart().getContents(), false, false);
        for (String key : map.keySet()) {
            String val = map.get(key);
            xml = xml.replaceAll("\\$\\{" + Pattern.quote(key) + "\\}",
                    val == null ? "" : "<![CDATA[" + val + "]]>");
        }
        mlp.getMainDocumentPart().setContents(
                (org.docx4j.wml.Document) XmlUtils.unmarshalString(xml));
    }

    // ── header / footer replacement ──────────────────────────────────────────

    private void replaceHeaderAndFooter(final Map<String, String> replacements) {
        try {
            if (mlp.getHeaderFooterPolicy().getDefaultHeader() != null) {
                HeaderPart fp = mlp.getHeaderFooterPolicy().getDefaultHeader();
                String xml = XmlUtils.marshaltoString(fp.getContents(), false, true);
                for (String key : replacements.keySet())
                    xml = xml.replace("${" + key + "}", replacements.get(key));
                mlp.getHeaderFooterPolicy().getDefaultHeader()
                        .setContents((Hdr) XmlUtils.unmarshalString(xml));
            }
            if (mlp.getHeaderFooterPolicy().getFirstHeader() != null) {
                HeaderPart fp = mlp.getHeaderFooterPolicy().getFirstHeader();
                String xml = XmlUtils.marshaltoString(fp.getContents(), false, true);
                for (String key : replacements.keySet())
                    xml = xml.replace("${" + key + "}", replacements.get(key));
                mlp.getHeaderFooterPolicy().getFirstHeader()
                        .setContents((Hdr) XmlUtils.unmarshalString(xml));
            }
            if (mlp.getHeaderFooterPolicy().getEvenHeader() != null) {
                HeaderPart fp = mlp.getHeaderFooterPolicy().getEvenHeader();
                String xml = XmlUtils.marshaltoString(fp.getContents(), false, true);
                for (String key : replacements.keySet())
                    xml = xml.replace("${" + key + "}", replacements.get(key));
                mlp.getHeaderFooterPolicy().getEvenHeader()
                        .setContents((Hdr) XmlUtils.unmarshalString(xml));
            }
            if (mlp.getHeaderFooterPolicy().getDefaultFooter() != null) {
                FooterPart fp = mlp.getHeaderFooterPolicy().getDefaultFooter();
                String xml = XmlUtils.marshaltoString(fp.getContents(), false, true);
                for (String key : replacements.keySet())
                    xml = xml.replace("${" + key + "}", replacements.get(key));
                mlp.getHeaderFooterPolicy().getDefaultFooter()
                        .setContents((Ftr) XmlUtils.unmarshalString(xml));
            }
            if (mlp.getHeaderFooterPolicy().getFirstFooter() != null) {
                FooterPart fp = mlp.getHeaderFooterPolicy().getFirstFooter();
                String xml = XmlUtils.marshaltoString(fp.getContents(), false, true);
                for (String key : replacements.keySet())
                    xml = xml.replace("${" + key + "}", replacements.get(key));
                mlp.getHeaderFooterPolicy().getFirstFooter()
                        .setContents((Ftr) XmlUtils.unmarshalString(xml));
            }
            if (mlp.getHeaderFooterPolicy().getEvenFooter() != null) {
                FooterPart fp = mlp.getHeaderFooterPolicy().getEvenFooter();
                String xml = XmlUtils.marshaltoString(fp.getContents(), false, true);
                for (String key : replacements.keySet())
                    xml = xml.replace("${" + key + "}", replacements.get(key));
                mlp.getHeaderFooterPolicy().getEvenFooter()
                        .setContents((Ftr) XmlUtils.unmarshalString(xml));
            }
        } catch (JAXBException | Docx4JException e) {
            e.printStackTrace();
        }
    }

    // ── HTML replacement in document ─────────────────────────────────────────

    private void replaceHTML(final Object mainPart,
                              final Map<String, List<Object>> replacements) {
        replaceHTML(mainPart, replacements, true);
    }

    private void replaceHTML(final Object mainPart,
                              final Map<String, List<Object>> replacements,
                              boolean once) {
        if (mainPart == null) return;
        if (replacements == null) throw new NullPointerException("replacements may not be null!");

        List<P> paragraphs = getParagraphs(mainPart);
        for (final P paragraph : paragraphs) {
            final StringWriter paragraphText = new StringWriter();
            try {
                TextUtils.extractText(paragraph, paragraphText);
            } catch (Exception ignored) {}
            final String identifier = paragraphText.toString().trim();
            if (identifier != null && replacements.containsKey(identifier)) {
                final List<Object> listToModify = getUpdatableElements(paragraph);
                if (listToModify != null) {
                    final int index = listToModify.indexOf(paragraph);
                    if (index <= -1)
                        throw new IllegalStateException("could not locate the paragraph in the specified list!");
                    listToModify.remove(index);
                    listToModify.addAll(index, replacements.get(identifier));
                    if (once) replacements.remove(identifier);
                }
            }
        }
    }

    // ── hyperlink replacement ────────────────────────────────────────────────

    public void replaceHyperlink(Object wordPackage, String searchText, String newUrl) {
        try {
            List<P.Hyperlink> hyperlinks = getHyperLinks(wordPackage);
            for (P.Hyperlink hyperlink : hyperlinks) {
                String hyperlinkText = getHyperlinkDisplayText(hyperlink);
                if (hyperlinkText != null && hyperlinkText.contains(searchText)) {
                    String updatedHyperlink = hyperlinkText.replace(searchText, newUrl);
                    RelationshipsPart relsPart =
                            mlp.getMainDocumentPart().getRelationshipsPart();
                    org.docx4j.relationships.Relationship newRel =
                            new org.docx4j.relationships.Relationship();
                    newRel.setId(relsPart.getNextId());
                    newRel.setType("http://schemas.openxmlformats.org/officeDocument/2006/"
                            + "relationships/hyperlink");
                    String updatedTarget = updatedHyperlink;
                    if (updatedTarget.contains("@")) {
                        updatedTarget = "mailto:" + updatedTarget;
                    } else if (!updatedTarget.startsWith("http")) {
                        updatedTarget = "https://" + updatedTarget;
                    }
                    if (searchText.contains("cvssString link")) {
                        updatedTarget = data.isCvss31()
                                ? "https://www.first.org/cvss/calculator/3-1#" + newUrl
                                : "https://www.first.org/cvss/calculator/4-0#" + newUrl;
                    }
                    newRel.setTarget(updatedTarget);
                    newRel.setTargetMode("External");
                    relsPart.getRelationships().getRelationship().add(newRel);
                    hyperlink.setId(newRel.getId());
                    updateHyperlinkDisplayText(hyperlink, updatedHyperlink);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getHyperlinkDisplayText(P.Hyperlink hyperlink) {
        StringBuilder text = new StringBuilder();
        for (Object obj : hyperlink.getContent()) {
            if (obj instanceof R) {
                R run = (R) obj;
                for (Object runContent : run.getContent()) {
                    if (runContent instanceof Text) {
                        text.append(((Text) runContent).getValue());
                    } else if (runContent instanceof JAXBElement) {
                        JAXBElement<?> element = (JAXBElement<?>) runContent;
                        if (element.getValue() instanceof Text) {
                            text.append(((Text) element.getValue()).getValue());
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    private void updateHyperlinkDisplayText(P.Hyperlink hyperlink, String newText) {
        hyperlink.getContent().clear();
        R run = new R();
        RPr runProps = new RPr();
        RStyle hyperlinkStyle = new RStyle();
        hyperlinkStyle.setVal("Hyperlink");
        runProps.setRStyle(hyperlinkStyle);
        run.setRPr(runProps);
        Text text = new Text();
        text.setValue(newText);
        run.getContent().add(text);
        hyperlink.getContent().add(run);
    }

    // ── figure-variable replacement ──────────────────────────────────────────

    public String replaceFigureVariables(String text, int index) {
        Pattern pattern = Pattern.compile("\\$\\{Figure#\\.(\\d+)\\}");
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String subNumber = matcher.group(1);
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement("Figure " + index + "." + subNumber));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // ── TOC ──────────────────────────────────────────────────────────────────

    public void tocGenerator(WordprocessingMLPackage mlp) {
        try {
            TocGenerator tocGenerator = new TocGenerator(mlp);
            int index = getIndex(mlp.getMainDocumentPart(), "${TOC}");
            if (index == -1) return;
            tocGenerator.generateToc(index, "TOC \\o \"1-3\" \\h \\z \\u ",
                    STTabTlc.DOT, true);
            addPageBreak(mlp, index + 1);
        } catch (TocException e) {
            e.printStackTrace();
        }
    }

    // ── keyword key lookup ───────────────────────────────────────────────────

    private static final String[] KEYWORDS = {
            "asmtName", "asmtId", "asmtAppid", "asmtAssessor", "asmtAssessor_Email",
            "asmtAssessor_Lines", "asmtAssessor_Comma", "asmtAssessor_Bullets",
            "remediation", "asmtTeam", "asmtType",
            "today", "asmtStart", "asmtEnd", "asmtAccessKey",
            "totalOpenVulns", "totalClosedVulns"
    };

    private String getKey(String key) {
        for (String word : KEYWORDS) {
            if (word.toLowerCase().equals(key.toLowerCase())) return word;
        }
        return "assessment.nothing";
    }

    // ── low-level docx4j helpers ─────────────────────────────────────────────

    private List<Object> getAllElementFromObject(Object obj, Class<?> toSearch) {
        List<Object> result = new ArrayList<>();
        if (obj instanceof JAXBElement) obj = ((JAXBElement<?>) obj).getValue();
        if (obj.getClass().equals(toSearch)) {
            result.add(obj);
        } else if (obj instanceof ContentAccessor) {
            for (Object child : ((ContentAccessor) obj).getContent()) {
                result.addAll(getAllElementFromObject(child, toSearch));
            }
        }
        return result;
    }

    private int changeColorOfCell(Tr row, String variable, String color) {
        for (Object para : getAllElementFromObject(row, P.class)) {
            if (matchText((P) para, variable)) {
                Tc cell = (Tc) ((P) para).getParent();
                if (cell.getTcPr().getShd() != null) {
                    cell.getTcPr().getShd().setFill(color);
                } else {
                    CTShd shader = new CTShd();
                    shader.setColor("auto");
                    shader.setFill(color);
                    cell.getTcPr().setShd(shader);
                }
            }
        }
        return -1;
    }

    private int changeColorOfText(Tr row, String variable, String color) {
        for (Object para : getAllElementFromObject(row, P.class)) {
            if (matchText((P) para, variable)) {
                for (Object o : ((P) para).getContent()) {
                    if (o.getClass().getName().equals("org.docx4j.wml.R")) {
                        BooleanDefaultTrue setBold = new BooleanDefaultTrue();
                        setBold.setVal(false);
                        BooleanDefaultTrue setI = new BooleanDefaultTrue();
                        setI.setVal(false);
                        if (((R) o).getRPr() != null) {
                            if (((R) o).getRPr().getB() != null
                                    && ((R) o).getRPr().getB().isVal()) setBold.setVal(true);
                            if (((R) o).getRPr().getI() != null
                                    && ((R) o).getRPr().getI().isVal()) setI.setVal(true);
                        }
                        org.docx4j.wml.ObjectFactory factory =
                                new org.docx4j.wml.ObjectFactory();
                        org.docx4j.wml.RPr rpr = factory.createRPr();
                        org.docx4j.wml.Color colr = factory.createColor();
                        colr.setVal(color);
                        rpr.setColor(colr);
                        rpr.setB(setBold);
                        rpr.setI(setI);
                        ((R) o).setRPr(rpr);
                    }
                }
            }
        }
        return -1;
    }

    private int indexOfRow(Tbl table, List<Object> paragraphs, String variable) {
        for (Object para : paragraphs) {
            if (matchText((P) para, variable)) {
                Tc cell = (Tc) ((P) para).getParent();
                if (cell.getParent().getClass().getName().equals("org.docx4j.wml.Tr")) {
                    return table.getContent().indexOf((Tr) cell.getParent());
                } else {
                    JAXBElement jrow = (JAXBElement) cell.getParent();
                    for (Object oRow : table.getContent()) {
                        Tr row = (Tr) oRow;
                        if (row.getContent().indexOf(jrow) >= 0) {
                            return table.getContent().indexOf(row);
                        }
                    }
                }
            }
        }
        return -1;
    }

    private boolean matchText(P paragraph, String variable) {
        final StringWriter paragraphText = new StringWriter();
        try {
            TextUtils.extractText(paragraph, paragraphText);
        } catch (Exception ex) {
            return false;
        }
        final String identifier = paragraphText.toString();
        return identifier != null && identifier.startsWith(variable);
    }

    private List<P> getParagraphs(final Object mainPart) {
        final List<P> paragraphs = new ArrayList<>();
        new TraversalUtil(mainPart, new TraversalUtil.CallbackImpl() {
            @Override
            public List<Object> apply(Object o) {
                if (o instanceof P) paragraphs.add((P) o);
                return null;
            }
        });
        return paragraphs;
    }

    private List<P.Hyperlink> getHyperLinks(final Object mainPart) {
        final List<P.Hyperlink> links = new ArrayList<>();
        new TraversalUtil(mainPart, new TraversalUtil.CallbackImpl() {
            @Override
            public List<Object> apply(Object o) {
                if (o instanceof P.Hyperlink) links.add((P.Hyperlink) o);
                return null;
            }

            @Override
            public boolean shouldTraverse(Object o) {
                return true;
            }
        });
        return links;
    }

    private List<Object> getUpdatableElements(P paragraph) {
        if (paragraph.getParent() instanceof Tc) {
            return ((Tc) paragraph.getParent()).getContent();
        } else if (paragraph.getParent() instanceof Hdr) {
            return ((Hdr) paragraph.getParent()).getContent();
        } else if (paragraph.getParent() instanceof CTTxbxContent) {
            return ((CTTxbxContent) paragraph.getParent()).getContent();
        } else {
            return mlp.getMainDocumentPart().getContent();
        }
    }

    private void addPageBreak(WordprocessingMLPackage mlp, int index) {
        org.docx4j.wml.ObjectFactory wmlObjectFactory = Context.getWmlObjectFactory();
        P p = wmlObjectFactory.createP();
        R r = wmlObjectFactory.createR();
        p.getContent().add(r);
        Br br = wmlObjectFactory.createBr();
        r.getContent().add(br);
        br.setType(org.docx4j.wml.STBrType.PAGE);
        mlp.getMainDocumentPart().getContent().add(index, p);
    }

    private int getIndex(final MainDocumentPart mainPart, String keyword) {
        if (mainPart == null) throw new NullPointerException("the supplied main doc part may not be null!");
        final List<P> paragraphs = getParagraphs(mainPart);
        for (final P paragraph : paragraphs) {
            final StringWriter paragraphText = new StringWriter();
            try {
                TextUtils.extractText(paragraph, paragraphText);
            } catch (Exception ignored) {}
            final String identifier = paragraphText.toString();
            if (identifier != null && identifier.contains(keyword)) {
                int index = mainPart.getContent().indexOf(paragraph);
                if (index == -1) return -1;
                mainPart.getContent().remove(index);
                return index;
            }
        }
        return -1;
    }

    private String getMatchingText(P paragraph, String variable) {
        final StringWriter paragraphText = new StringWriter();
        try {
            TextUtils.extractText(paragraph, paragraphText);
        } catch (Exception ex) {
            return null;
        }
        final String identifier = paragraphText.toString();
        if (identifier != null && identifier.startsWith(variable)) return identifier;
        return null;
    }

    private String getMatchingText(List<Object> paragraphs, String variable) {
        for (Object paragraph : paragraphs) {
            String text = getMatchingText((P) paragraph, variable);
            if (text != null) return text;
        }
        return null;
    }
}
