package com.couple.note;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 笔记纯文本抽取器（仅依赖 JDK，不引入 Apache POI）
 *
 * - MD / TXT：先按 UTF-8 严格解码，失败退回 GBK（中文 Windows 记事本常见编码）
 * - DOCX：docx 是 ZIP 容器，正文在 word/document.xml，用 StAX 流式抽取 w:t 文本节点
 *
 * 取舍（相对 POI）：
 * - 不解析页眉/页脚/脚注/批注（它们在别的 XML part 里），也不保留样式与图片
 * - 表格会被"拍平"成按行拼接的纯文本
 * - 对"笔记检索与预览"这个用途足够；若将来要还原排版，再换 POI 的 XWPFDocument
 */
@Slf4j
@Component
public class NoteTextExtractor {

    /** docx 正文所在条目 */
    private static final String DOCX_BODY_ENTRY = "word/document.xml";
    /** 单篇笔记抽取字符上限：防止超大文档把 DB 和 ingest 请求撑爆 */
    private static final int MAX_CHARS = 200_000;

    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 抽取纯文本；无法解析时抛出异常，由调用方决定是否标记解析失败
     */
    public String extract(NoteFormat format, byte[] bytes) throws IOException {
        String text = switch (format) {
            case MD, TXT -> decodePlainText(bytes);
            case DOCX -> extractDocx(bytes);
        };
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        return text.strip();
    }

    // ------------------------------------------------------------
    // 纯文本：编码探测
    // ------------------------------------------------------------

    /**
     * 先按 UTF-8 严格解码（任何非法字节都报错），失败则退回 GBK。
     * 这样无需额外引入编码探测库，也能覆盖绝大多数中文笔记。
     */
    private String decodePlainText(byte[] bytes) {
        byte[] body = stripUtf8Bom(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            log.debug("非 UTF-8 文本，按 GBK 解码，长度={}", body.length);
            return new String(body, GBK);
        }
    }

    private byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }

    // ------------------------------------------------------------
    // DOCX：ZIP 解包 + StAX 抽取
    // ------------------------------------------------------------

    private String extractDocx(byte[] bytes) throws IOException {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (DOCX_BODY_ENTRY.equals(entry.getName())) {
                    parseDocumentXml(zip, out);
                    break;
                }
            }
        }
        if (out.length() == 0) {
            throw new IOException("docx 中未找到可提取的正文（" + DOCX_BODY_ENTRY + "）");
        }
        return out.toString();
    }

    /**
     * 流式解析 word/document.xml：
     * - w:t   → 文本节点（可能被拆成多个 CHARACTERS 事件，需累积）
     * - w:p   → 段落结束，补换行
     * - w:tab → 制表符；w:br / w:cr → 换行
     */
    private void parseDocumentXml(InputStream xml, StringBuilder out) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 关闭 DTD 与外部实体，防 XXE
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(xml);
            boolean inText = false;
            StringBuilder run = new StringBuilder();

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "t" -> {
                            inText = true;
                            run.setLength(0);
                        }
                        case "tab" -> out.append('\t');
                        case "br", "cr" -> out.append('\n');
                        default -> { /* 其它元素忽略 */ }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    if (inText) {
                        run.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "t" -> {
                            inText = false;
                            out.append(run);
                        }
                        case "p" -> out.append('\n');
                        default -> { /* 其它元素忽略 */ }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("docx 正文 XML 解析失败: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignore) {
                    // 关闭失败无需处理
                }
            }
        }
    }
}
