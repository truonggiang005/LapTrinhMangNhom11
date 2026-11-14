package vn.edu.ptit.exam.ui.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Quản lý danh mục môn thi trên Client.
 * Lưu trữ bền vững vào file subjects.json và đồng bộ với ngân hàng câu hỏi.
 */
public class SubjectManager {

    private static final Logger log = LoggerFactory.getLogger(SubjectManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final File STORAGE_FILE = new File("data/subjects.json");

    private static final SubjectManager INSTANCE = new SubjectManager();

    public static SubjectManager getInstance() {
        return INSTANCE;
    }

    public static class SubjectItem {
        private String code;
        private String name;
        private String description;
        private int questionCount;

        public SubjectItem() {}

        public SubjectItem(String code, String name, String description) {
            this.code = code;
            this.name = name;
            this.description = description;
            this.questionCount = 0;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public int getQuestionCount() {
            return questionCount;
        }

        public void setQuestionCount(int questionCount) {
            this.questionCount = questionCount;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<SubjectItem> subjects = new CopyOnWriteArrayList<>();

    private SubjectManager() {
        loadFromStorage();
    }

    private synchronized void loadFromStorage() {
        subjects.clear();
        if (STORAGE_FILE.exists()) {
            try {
                List<SubjectItem> loaded = mapper.readValue(STORAGE_FILE, new TypeReference<List<SubjectItem>>() {});
                if (loaded != null && !loaded.isEmpty()) {
                    subjects.addAll(loaded);
                    return;
                }
            } catch (IOException e) {
                log.warn("Lỗi đọc file môn học {}: {}", STORAGE_FILE.getPath(), e.getMessage());
            }
        }

        // Mặc định ban đầu
        subjects.add(new SubjectItem("LTM", "Lập trình mạng", "Kiến trúc máy chủ/máy trạm, giao thức mạng TCP/IP, lập trình đa luồng"));
        subjects.add(new SubjectItem("CSDL", "Cơ sở dữ liệu", "Hệ quản trị CSDL quan hệ, SQL, giao dịch ACID"));
        subjects.add(new SubjectItem("CTDL_GT", "Cấu trúc dữ liệu và giải thuật", "Cây, đồ thị, thuật toán sắp xếp và tìm kiếm"));
        subjects.add(new SubjectItem("HDH", "Hệ điều hành", "Quản lý tiến trình, bộ nhớ, luồng và vào ra"));
        subjects.add(new SubjectItem("KTMT", "Kiến trúc máy tính", "Kiến trúc bộ xử lý, tập lệnh và phân cấp nhớ"));
        saveToStorage();
    }

    public synchronized void saveToStorage() {
        try {
            File parent = STORAGE_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(STORAGE_FILE, subjects);
        } catch (IOException e) {
            log.error("Lỗi lưu file môn học {}: {}", STORAGE_FILE.getPath(), e.getMessage());
        }
    }

    public List<SubjectItem> getSubjects() {
        return new ArrayList<>(subjects);
    }

    public List<String> getSubjectNames() {
        List<String> names = new ArrayList<>();
        for (SubjectItem s : subjects) {
            names.add(s.getName());
        }
        return names;
    }

    public synchronized boolean addSubject(String code, String name, String description) {
        if (code == null || code.trim().isEmpty() || name == null || name.trim().isEmpty()) {
            return false;
        }
        String cleanCode = code.trim().toUpperCase();
        String cleanName = name.trim();

        for (SubjectItem s : subjects) {
            if (s.getCode().equalsIgnoreCase(cleanCode) || s.getName().equalsIgnoreCase(cleanName)) {
                return false; // Đã tồn tại
            }
        }

        subjects.add(new SubjectItem(cleanCode, cleanName, description != null ? description.trim() : ""));
        saveToStorage();
        return true;
    }

    public synchronized boolean deleteSubject(String code) {
        boolean removed = subjects.removeIf(s -> s.getCode().equalsIgnoreCase(code));
        if (removed) {
            saveToStorage();
        }
        return removed;
    }

    public synchronized void mergeFromQuestionSubjects(Collection<String> foundSubjects) {
        boolean changed = false;
        for (String subjName : foundSubjects) {
            if (subjName == null || subjName.trim().isEmpty()) continue;
            String trimmed = subjName.trim();
            boolean exists = false;
            for (SubjectItem s : subjects) {
                if (s.getName().equalsIgnoreCase(trimmed)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                String code = "SUBJ_" + (subjects.size() + 1);
                subjects.add(new SubjectItem(code, trimmed, "Môn học từ ngân hàng câu hỏi"));
                changed = true;
            }
        }
        if (changed) {
            saveToStorage();
        }
    }
}
