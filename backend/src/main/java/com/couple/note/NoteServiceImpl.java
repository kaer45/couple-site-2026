package com.couple.note;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.couple.agent.AgentChatClient;
import com.couple.agent.IngestEvent;
import com.couple.common.BusinessException;
import com.couple.common.PageResult;
import com.couple.file.FileStorageService;
import com.couple.note.dto.NoteDetailVO;
import com.couple.note.dto.NoteUpdateRequest;
import com.couple.note.dto.NoteVO;
import com.couple.note.entity.Note;
import com.couple.note.mapper.NoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习笔记服务实现
 *
 * 数据流：上传原文件 → 抽取纯文本 → 入库 → 发布 IngestEvent 同步向量库
 * - 解析失败**不影响上传**：原文件照常保存可下载，只是不参与搜索与 AI 检索
 * - AI 数据域固定为 personal（scope_id = userId），对应命名空间 note_u{userId}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteServiceImpl implements NoteService {

    /** 列表卡片用的正文节选长度：只截前面一段做卡片预览，避免把整篇正文发给前端 */
    private static final int EXCERPT_LENGTH = 300;
    /** parse_error 列长度上限 */
    private static final int PARSE_ERROR_MAX = 255;

    private final NoteMapper noteMapper;
    private final FileStorageService fileStorageService;
    private final NoteTextExtractor textExtractor;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentChatClient agentChatClient;

    @Override
    public PageResult<NoteVO> page(Long userId, long current, long size, String keyword) {
        // 列表不加载完整正文（docx 抽取可达 20 万字），但摘要要来自正文，
        // 所以让 SQL 只截取前面一段：LEFT(content_text, N) AS summary → 映射到 Note.summary（非表字段）
        QueryWrapper<Note> wrapper = new QueryWrapper<Note>()
                .eq("user_id", userId)
                .select("id", "user_id", "title", "format", "original_name", "original_url",
                        "file_size", "parse_status", "parse_error", "summary_ai", "summary_ai_at",
                        "created_at", "updated_at",
                        "LEFT(content_text, " + EXCERPT_LENGTH + ") AS summary")
                .orderByDesc("created_at");

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("title", kw).or().like("content_text", kw));
        }

        Page<Note> page = noteMapper.selectPage(new Page<>(current, size), wrapper);
        List<NoteVO> records = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(page, records);
    }

    @Override
    public NoteDetailVO detail(Long userId, Long id) {
        return toDetailVO(requireOwnedNote(userId, id));
    }

    @Override
    public NoteVO upload(Long userId, MultipartFile file, String title) {
        NoteFormat format = NoteFormat.fromFilename(file.getOriginalFilename());
        if (format == null) {
            throw new BusinessException(400, "仅支持 .md / .markdown / .txt / .docx 格式的笔记文件");
        }

        // 1) 先把内容读成字节：它既是落盘源，也是解析源。
        //    注意不能"先 storeDocument 再 file.getBytes()"——transferTo 写完会删掉容器临时文件，
        //    之后再读必然 FileNotFoundException（曾经的 bug）。
        if (file.getSize() > FileStorageService.MAX_DOCUMENT_SIZE) {
            throw new BusinessException(400, "笔记文件不能超过10MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(500, "读取上传文件失败");
        }
        String url = fileStorageService.storeDocument(bytes, file.getOriginalFilename());

        Note note = new Note();
        note.setUserId(userId);
        note.setTitle(resolveTitle(title, file.getOriginalFilename()));
        note.setFormat(format.name());
        note.setOriginalName(file.getOriginalFilename());
        note.setOriginalUrl(url);
        note.setFileSize((long) bytes.length);

        // 2) 抽取纯文本；失败不影响入库
        try {
            String text = textExtractor.extract(format, bytes);
            note.setContentText(text);
            note.setParseStatus(Note.PARSE_OK);
        } catch (IOException | RuntimeException e) {
            log.warn("笔记解析失败 originalName={}, err={}", file.getOriginalFilename(), e.getMessage());
            note.setParseStatus(Note.PARSE_FAILED);
            note.setParseError(truncate("解析失败：" + e.getMessage(), PARSE_ERROR_MAX));
        }

        noteMapper.insert(note);

        // 3) 解析成功才同步向量库（没有正文就没有可检索的内容）
        if (Note.PARSE_OK == note.getParseStatus()) {
            eventPublisher.publishEvent(new IngestEvent("note", note.getId(),
                    buildIngestText(note), "personal", userId, "upsert"));
        }

        return toVO(note);
    }

    @Override
    public NoteVO update(Long userId, Long id, NoteUpdateRequest request) {
        Note note = requireOwnedNote(userId, id);
        note.setTitle(request.getTitle().trim());
        noteMapper.updateById(note);

        // 标题参与向量文本，改标题后重新同步
        if (Note.PARSE_OK == note.getParseStatus()) {
            eventPublisher.publishEvent(new IngestEvent("note", note.getId(),
                    buildIngestText(note), "personal", userId, "upsert"));
        }
        return toVO(note);
    }

    @Override
    public NoteDetailVO generateSummary(Long userId, Long id) {
        Note note = requireOwnedNote(userId, id);
        if (!StringUtils.hasText(note.getContentText())) {
            throw new BusinessException(400, "该笔记没有可用的正文，无法生成摘要");
        }

        AgentChatClient.SummarizeResult result = agentChatClient.summarize(
                userId, note.getId(), note.getTitle(), note.getContentText());

        if (!result.isOk()) {
            // 失败**不写库**：否则会把"服务不可用"这类提示当成摘要存下来，
            // 之后前端会把它当作正常的 AI 摘要展示，很难排查。
            throw new BusinessException(503, "生成摘要失败：" + summarizeFailureMessage(result.getErrorType()));
        }

        note.setSummaryAi(result.getSummary());
        note.setSummaryAiAt(LocalDateTime.now());
        noteMapper.updateById(note);
        return toDetailVO(note);
    }

    @Override
    public void delete(Long userId, Long id) {
        Note note = requireOwnedNote(userId, id);

        noteMapper.deleteById(id);
        fileStorageService.delete(note.getOriginalUrl());
        eventPublisher.publishEvent(new IngestEvent("note", id, null, "personal", userId, "delete"));
    }

    // ------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------

    /** 校验笔记存在且属于当前用户（隐私隔离核心） */
    private Note requireOwnedNote(Long userId, Long id) {
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
        return note;
    }

    /** 标题为空时取原文件名（去扩展名） */
    private String resolveTitle(String title, String originalName) {
        if (StringUtils.hasText(title)) {
            return truncate(title.trim(), 200);
        }
        if (!StringUtils.hasText(originalName)) {
            return "未命名笔记";
        }
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        return truncate(base, 200);
    }

    /** 向量化文本 = 标题 + 正文（标题权重靠前，检索更准） */
    private String buildIngestText(Note note) {
        String content = note.getContentText() == null ? "" : note.getContentText();
        return note.getTitle() + "\n" + content;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * 卡片摘要素材：正文前 EXCERPT_LENGTH 字的**原始节选**（保留换行）。
     * 刻意不清洗 Markdown 语法：前端用 markdown-it 解析后渲染，能正确显示标题/粗体/代码等。
     */
    private String excerpt(Note note) {
        String src = note.getSummary() != null ? note.getSummary() : note.getContentText();
        if (src == null || src.isBlank()) {
            return null;
        }
        return truncate(src, EXCERPT_LENGTH);
    }

    /** 把 Agent 的错误类型转成面向用户的摘要失败提示 */
    private String summarizeFailureMessage(String errorType) {
        return switch (errorType == null ? "" : errorType) {
            case "DISABLED" -> "AI 能力未启用（AGENT_ENABLED=false）";
            case "CIRCUIT_OPEN", "UNREACHABLE" -> "AI 服务不可达，请确认 Python 服务已启动";
            case "TIMEOUT" -> "AI 服务响应超时";
            default -> "AI 服务返回错误";
        };
    }

    private NoteVO toVO(Note note) {
        NoteVO vo = new NoteVO();
        vo.setId(note.getId());
        vo.setTitle(note.getTitle());
        vo.setFormat(note.getFormat());
        vo.setOriginalName(note.getOriginalName());
        vo.setOriginalUrl(note.getOriginalUrl());
        vo.setFileSize(note.getFileSize());
        vo.setParseStatus(note.getParseStatus());
        vo.setParseError(note.getParseError());
        vo.setSummaryAi(note.getSummaryAi());
        vo.setSummaryAiAt(note.getSummaryAiAt());
        // 卡片摘要素材：分页走 SQL 节选（summary 字段），上传/更新走内存里的完整正文。
        // 这里**不做 Markdown 清洗**——保留原文，交给前端用 markdown-it 渲染成卡片摘要。
        vo.setSummary(excerpt(note));
        vo.setCreatedAt(note.getCreatedAt());
        vo.setUpdatedAt(note.getUpdatedAt());
        return vo;
    }

    private NoteDetailVO toDetailVO(Note note) {
        NoteDetailVO vo = new NoteDetailVO();
        vo.setId(note.getId());
        vo.setTitle(note.getTitle());
        vo.setFormat(note.getFormat());
        vo.setOriginalName(note.getOriginalName());
        vo.setOriginalUrl(note.getOriginalUrl());
        vo.setFileSize(note.getFileSize());
        vo.setParseStatus(note.getParseStatus());
        vo.setParseError(note.getParseError());
        vo.setContent(note.getContentText());
        vo.setSummaryAi(note.getSummaryAi());
        vo.setSummaryAiAt(note.getSummaryAiAt());
        vo.setCreatedAt(note.getCreatedAt());
        vo.setUpdatedAt(note.getUpdatedAt());
        return vo;
    }
}
