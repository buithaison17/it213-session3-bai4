package com.example.bai4;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class CandidateService {
    private final ChatModel chatModel;
    private final CandidateRepository candidateRepository;

    public CandidateExtraction extraction(String content) {
        BeanOutputConverter<CandidateExtraction> converter = new BeanOutputConverter<>(CandidateExtraction.class);
        String template = """
                VAI TRÒ:
                Bạn là một AI chuyên phân tích và trích xuất thông tin từ CV ứng viên.
                Bạn có nhiệm vụ chuyển đổi nội dung CV dạng văn bản thô, phi cấu trúc
                thành dữ liệu có cấu trúc phục vụ hệ thống quản lý tuyển dụng của Rikkei Academy.
                
                MỤC TIÊU:
                Phân tích toàn bộ nội dung CV được cung cấp và trích xuất chính xác
                các thông tin sau:
                1. fullName:
                   - Họ và tên đầy đủ của ứng viên.
                2. phone:
                   - Số điện thoại của ứng viên.
                3. email:
                   - Địa chỉ email của ứng viên.
                4. skills:
                   - Danh sách các kỹ năng chuyên môn được đề cập trong CV.
                   - Mỗi kỹ năng phải là một phần tử riêng biệt trong danh sách.
                5. yearsExperience:
                   - Tổng số năm kinh nghiệm làm việc của ứng viên.
                
                NGỮ CẢNH:
                CV của ứng viên được cung cấp dưới dạng văn bản thô:
                {resumeText}
                Hãy đọc và phân tích TOÀN BỘ nội dung CV trước khi đưa ra kết quả.
                Thông tin có thể xuất hiện ở nhiều vị trí khác nhau trong CV,
                ví dụ:
                - Thông tin cá nhân.
                - Career Objective.
                - Work Experience.
                - Employment History.
                - Technical Skills.
                - Professional Skills.
                - Education.
                - Projects.
                Các thông tin trong CV có thể được viết bằng tiếng Việt hoặc tiếng Anh.
                
                RÀNG BUỘC NGHIÊM NGẶT:
                1. Chỉ sử dụng thông tin thực sự xuất hiện trong CV.
                2. Không được tự suy đoán hoặc tự tạo ra thông tin không có trong CV.
                3. Phải phân tích toàn bộ CV trước khi trích xuất dữ liệu.
                4. Nếu không tìm thấy fullName, trả về null.
                5. Nếu không tìm thấy phone, trả về null.
                6. Nếu không tìm thấy email, trả về null.
                7. Nếu không tìm thấy skills, trả về danh sách rỗng [].
                8. Nếu không xác định được yearsExperience, trả về null.
                9. skills phải là danh sách String.
                10. Không được đưa mô tả hoặc giải thích vào trong danh sách skills.
                11. Loại bỏ các kỹ năng bị trùng lặp.
                12. Giữ nguyên tên kỹ năng theo cách phổ biến trong CV.
                Ví dụ:
                - Java
                - Spring Boot
                - React
                - Docker
                - PostgreSQL
                13. yearsExperience phải là số nguyên.
                14. Nếu CV ghi kinh nghiệm theo tháng nhưng chưa đủ một năm,
                không được tự ý làm tròn thành 1 năm.
                15. Nếu CV có nhiều vị trí công việc, hãy phân tích toàn bộ lịch sử
                làm việc để xác định tổng số năm kinh nghiệm phù hợp với thông tin
                thực tế được cung cấp.
                16. Không được suy đoán yearsExperience chỉ dựa trên tuổi,
                thời gian học tập hoặc thời gian tốt nghiệp.
                17. Không được thêm field ngoài các field được yêu cầu.
                18. Chỉ trả về dữ liệu theo đúng cấu trúc được cung cấp bởi
                formatInstructions.
                19. Không được trả về Markdown.
                20. Không được sử dụng code fence như ```json.
                21. Không được thêm lời giải thích, nhận xét hoặc text trước/sau JSON.
                22. Kết quả phải là JSON hợp lệ và có thể parse trực tiếp.
                
                ĐỊNH DẠNG ĐẦU RA:
                {formatInstructions}
                """;

        Prompt prompt = new PromptTemplate(template)
                .create(Map.of("resumeText", content, "formatInstructions", converter.getFormat()));

        String response = chatModel.call(prompt).getResult().getOutput().getText();
        return converter.convert(response);
    }

    public Candidate addCandidate(String resumeText) {
        CandidateExtraction candidateExtraction = extraction(resumeText);
        // Validate dữ liệu đầu vò
        if (candidateExtraction.fullName() == null || candidateExtraction.fullName().isBlank()) {
            throw new IllegalArgumentException("Họ tên không được để trống");
        }
        if (candidateExtraction.yearsExperience() == null || candidateExtraction.yearsExperience() <= 0) {
            throw new IllegalArgumentException("Số năm kinh nghiệm phải lớn hơn 0");
        }

        Candidate candidate = Candidate.builder()
                .fullName(candidateExtraction.fullName())
                .phone(candidateExtraction.phone())
                .email(candidateExtraction.email())
                .skills(String.join(", ", candidateExtraction.skills()))
                .yearsExperience(candidateExtraction.yearsExperience())
                .build();

        return candidateRepository.save(candidate);
    }
}
