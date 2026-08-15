# Bài 4 — ETL Resume Parser: ASCII Flow & @Transactional Trade-off

## 1. Sơ đồ ASCII mô tả chi tiết luồng dữ liệu ETL

```text
+-----------------------+
|     Client / HR       |
|   CV dạng text thô    |
+-----------+-----------+
            |
            | resumeText
            v
+-----------------------+
|   CandidateETLService |
+-----------+-----------+
            |
            | 1. EXTRACT
            v
+-----------------------+
|    PromptTemplate     |
|  - Vai trò AI         |
|  - Mục tiêu           |
|  - CV text             |
|  - formatInstructions |
+-----------+-----------+
            |
            | 2. TRANSFORM
            | ChatModel.call()
            v
+-----------------------+
|          LLM          |
| OpenAI / Ollama / ... |
+-----------+-----------+
            |
            | JSON có cấu trúc
            v
+-----------------------+
| BeanOutputConverter   |
| JSON -> Candidate     |
|       Extraction      |
+-----------+-----------+
            |
            | CandidateExtraction
            v
+-----------------------+
|      VALIDATION       |
|                       |
| fullName != blank     |
| email hợp lệ          |
| yearsExperience >= 0  |
+-----------+-----------+
            |
            | Valid
            v
+-----------------------+
|      JPA Mapping      |
| CandidateExtraction   |
|         ->            |
|      Candidate        |
+-----------+-----------+
            |
            | repository.save()
            v
+-----------------------+
|      SQL Database     |
| candidates / skills   |
+-----------------------+
            |
            v
       Save Success
```

### Các bước ETL

**Extract:** nhận CV dạng text thô qua `processResume(String resumeText)`.

**Transform:** gửi CV cho LLM bằng `ChatModel`, sau đó dùng `BeanOutputConverter<CandidateExtraction>` để chuyển JSON thành Java Record.

**Load:** validate dữ liệu, mapping sang `Candidate`, rồi `CandidateRepository.save()` xuống SQL.

---

# 2. Trade-off khi dùng @Transactional với LLM Call

Giả sử:

```java
@Transactional
public Candidate processResume(String resumeText) {

    CandidateExtraction extraction =
            callLLM(resumeText);

    validate(extraction);

    Candidate candidate =
            mapToEntity(extraction);

    return candidateRepository.save(candidate);
}
```

Về mặt code, cách này đơn giản nhưng có một vấn đề kiến trúc: **LLM là network call chậm và không ổn định, trong khi transaction database nên càng ngắn càng tốt.**

---

## 3. LLM Call nằm bên trong @Transactional

Luồng:

```text
BEGIN TRANSACTION
       |
       v
+----------------+
|   ChatModel    |
|    LLM API     |
|    5 - 20s     |
+-------+--------+
        |
        v
   Validation
        |
        v
 repository.save()
        |
        v
      COMMIT
```

### Ưu điểm

- Code đơn giản, toàn bộ flow nằm trong một method.
- Các thao tác database phát sinh trong transaction có thể rollback nếu xảy ra exception phù hợp.
- Dễ hiểu đối với bài toán nhỏ.

### Nhược điểm

LLM có thể mất vài giây hoặc hàng chục giây:

```text
Request
   |
   +---- Transaction
   |
   +---- LLM 15s
   |
   +---- Validation
   |
   +---- DB Save
   |
   +---- Commit
```

Điều này có thể làm transaction tồn tại lâu hơn cần thiết và có nguy cơ giữ database resources lâu hơn.

Ví dụ connection pool có 10 connection:

```text
Request 1 -> DB connection -> chờ LLM 15s
Request 2 -> DB connection -> chờ LLM 15s
...
Request 10 -> DB connection -> chờ LLM 15s

Request 11 -> phải chờ connection
```

Khi tải tăng, có thể dẫn tới connection pool exhaustion hoặc timeout.

---

# 4. LLM Call nằm ngoài @Transactional

Kiến trúc nên ưu tiên:

```text
             ChatModel / LLM
                    |
                    | 5 - 20s
                    v
          CandidateExtraction
                    |
                    v
               Validation
                    |
                    v
        +-----------------------+
        |   BEGIN TRANSACTION   |
        +-----------+-----------+
                    |
                    v
             repository.save()
                    |
                    v
        +-----------------------+
        |        COMMIT         |
        +-----------------------+
```

Ví dụ:

```java
public Candidate processResume(String resumeText) {

    CandidateExtraction extraction =
            callLLM(resumeText);

    validate(extraction);

    return saveCandidate(extraction);
}

@Transactional
public Candidate saveCandidate(
        CandidateExtraction extraction
) {
    Candidate candidate = mapToEntity(extraction);

    return candidateRepository.save(candidate);
}
```

Transaction chỉ bao quanh phần ghi database.

---

# 5. Ưu điểm của việc tách transaction

## 5.1. Không phải chờ LLM trong transaction

```text
LLM 15s
  |
  | Không cần DB transaction
  v
Validation
  |
  v
BEGIN TRANSACTION
  |
  v
SAVE
  |
  v
COMMIT
```

Database resources chỉ được sử dụng khi thực sự cần.

## 5.2. Tốt hơn cho connection pool

Nếu 100 request cùng gọi LLM:

```text
100 requests
     |
     +---- chờ LLM
     |
     v
CandidateExtraction
     |
     +---- transaction ngắn
     |
     v
Database
```

Thay vì 100 request đều cố giữ database resources trong thời gian chờ LLM.

## 5.3. Tăng khả năng scale

Latency của LLM thường lớn hơn rất nhiều so với thời gian thực hiện SQL.

Ví dụ:

```text
LLM call:       10 - 20 seconds
DB save:        vài milliseconds
```

Không nên để transaction database phụ thuộc vào thời gian phản hồi của LLM.

---

# 6. Nhược điểm của việc tách transaction

LLM và database không nằm trong cùng một transaction.

Ví dụ:

```text
LLM thành công
      |
      v
CandidateExtraction
      |
      v
Validation thành công
      |
      v
DB Save
      |
      X
   DB Error
```

Lúc này:

```text
Database -> rollback được
LLM      -> không rollback được
```

Request LLM đã thực hiện thì chi phí token vẫn có thể phát sinh.

Không có:

```text
ROLLBACK LLM
```

vì API LLM không tham gia transaction database.

---

# 7. So sánh

| Tiêu chí | LLM trong @Transactional | LLM ngoài @Transactional |
|---|---|---|
| Code đơn giản | Tốt | Tốt |
| Rollback DB | Thuận tiện | Thuận tiện cho phần DB |
| Transaction duration | Dài hơn | Ngắn |
| DB resources | Có nguy cơ bị giữ lâu | Sử dụng ngắn |
| Connection pool | Dễ chịu tải lớn hơn | Hiệu quả hơn |
| Khả năng scale | Thấp hơn | Cao hơn |
| Rollback LLM | Không thể | Không thể |
| Phù hợp production | Không ưu tiên | **Khuyến nghị** |

---

# 8. Lưu ý kỹ thuật về Connection

Không nên hiểu tuyệt đối rằng `@Transactional` luôn lấy JDBC connection ngay tại dòng đầu tiên và giữ nó liên tục đến cuối method.

Việc lấy connection và thời điểm Hibernate thực hiện SQL còn phụ thuộc vào transaction manager, JPA/Hibernate và thời điểm persistence operation thực sự xảy ra.

Tuy nhiên, về mặt kiến trúc, **network call chậm không nên nằm trong transaction boundary** nếu không có lý do nghiệp vụ đặc biệt.

Transaction dài làm tăng thời gian transaction tồn tại và có thể làm tăng việc sử dụng các database resources, đặc biệt khi hệ thống có tải đồng thời cao.

---

# 9. Kiến trúc khuyến nghị cho Resume Parser

```text
             CV TEXT
                |
                v
       +------------------+
       |   ChatModel      |
       |     LLM Call     |
       +--------+---------+
                |
                v
       +------------------+
       | BeanOutput       |
       | Converter        |
       +--------+---------+
                |
                v
       +------------------+
       | Business         |
       | Validation       |
       +--------+---------+
                |
                v
       +------------------+
       | @Transactional   |
       |                  |
       | repository.save()|
       +--------+---------+
                |
                v
          SQL Database
```

Nguyên tắc:

```text
LLM Call
   ↓
Transform
   ↓
Validation
   ↓
[ Transaction bắt đầu ]
   ↓
DB Save
   ↓
[ Commit ]
```

Không nên:

```text
[ Transaction bắt đầu ]
   ↓
LLM Call 15s
   ↓
Validation
   ↓
DB Save
   ↓
[ Commit ]
```

---

# 10. Kết luận

Đối với module ETL Resume Parser, nên gọi `ChatModel` **bên ngoài transaction** và chỉ mở transaction cho phần ghi dữ liệu vào database.

Lý do:

1. LLM là network call có latency cao.
2. Thời gian phản hồi LLM không ổn định.
3. Transaction database nên càng ngắn càng tốt.
4. Giảm nguy cơ database resources bị sử dụng lâu.
5. Giúp connection pool phục vụ được nhiều request hơn.
6. Tăng khả năng scale của hệ thống.
7. Rollback database vẫn được đảm bảo cho phần persistence.
8. Không thể rollback một request LLM đã thực hiện.

> **Nguyên tắc kiến trúc:** Transaction nên bao quanh phần dữ liệu cần tính nhất quán trong database, không nên bao quanh các network call chậm như LLM.

---

## 11. Mô hình ETL hoàn chỉnh

```text
+-------------+
|  CV Text    |
+------+------+
       |
       v
+-------------+
|  EXTRACT    |
| Receive CV  |
+------+------+
       |
       v
+-------------+
|  TRANSFORM  |
|  ChatModel  |
|     +       |
| Converter   |
+------+------+
       |
       v
+-------------+
|  VALIDATE   |
|             |
| Name        |
| Email       |
| Experience  |
+------+------+
       |
       | Valid
       v
+-------------+
|    LOAD     |
|             |
| Transaction |
|     ↓       |
| JPA save()  |
+------+------+
       |
       v
+-------------+
| SQL Database|
+-------------+
```
