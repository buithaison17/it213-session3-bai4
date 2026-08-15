package com.example.bai4;

import java.util.List;

public record CandidateExtraction(
        String fullName,
        String phone,
        String email,
        List<String> skills,
        Integer yearsExperience
) {
}
