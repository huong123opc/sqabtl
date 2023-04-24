package com.demo.service.impl;

import com.demo.dto.BaseResponse;
import com.demo.dto.request.StudentGradeReq;
import com.demo.dto.response.SubjectResponse;
import com.demo.exception.BaseErrorException;
import com.demo.model.*;
import com.demo.repository.*;
import com.demo.service.StudentGradeService;
import com.demo.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class StudentGradeServiceImpl implements StudentGradeService {
    private final SemesterOfStudentRepository semesterOfStudentRepository;
    private final SemesterRepository semesterRepository;
    private final StudentRepository studentRepository;
    private final SubjectOfStudentRepository subjectOfStudentRepository;
    private final SubjectRepository subjectRepository;
    private final AccountRepository accountRepository;
    private final JwtUtils jwtUtils;

    @Override
    public BaseResponse updateGradeForStudent(StudentGradeReq studentGradeReq) {
        Student student = studentRepository.findByStudentCode(studentGradeReq.getStudentCode())
                .orElseThrow(() -> new BaseErrorException(
                        "Student not found with student code : " + studentGradeReq.getStudentCode(), null
                ));
        Subject subject = subjectRepository.findBySubjectCode(studentGradeReq.getSubjectCode())
                .orElseThrow(() -> new BaseErrorException(
                        "Subject not found with subject code : " + studentGradeReq.getSubjectCode(), null
                ));
        Semester semester = semesterRepository.findById(studentGradeReq.getSemesterId())
                .orElseThrow(() -> new BaseErrorException(
                        "Semester not found with id: " + studentGradeReq.getSemesterId(), null
                ));
        SemesterOfStudent semesterOfStudent = semesterOfStudentRepository
                .findByStudent_StudentCodeAndSemester_Id(studentGradeReq.getStudentCode(), studentGradeReq.getSemesterId())
                .orElseGet(() -> semesterOfStudentRepository.save(new SemesterOfStudent(student, semester)));

        double finalPoint = studentGradeReq.getAttendancePoint() * 0.1 +
                studentGradeReq.getTestPoint() * 0.2 +
                studentGradeReq.getPracticePoint() * 0.2 +
                studentGradeReq.getExamPoint() * 0.5;

        String letterGrade = parseToLetterGrade(finalPoint);

        subjectOfStudentRepository.save(SubjectOfStudent.builder()
                .student(student)
                .subject(subject)
                .semesterOfStudent(semesterOfStudent)
                .attendancePoint(studentGradeReq.getAttendancePoint())
                .examPoint(studentGradeReq.getExamPoint())
                .testPoint(studentGradeReq.getTestPoint())
                .practicePoint(studentGradeReq.getPracticePoint())
                .finalPoint(finalPoint)
                .letterGrade(letterGrade)
                .gpa(parseToGpa(letterGrade))
                .build());

        int totalCredit = 0;
        double gpaInSemester = 0.0;
        List<SubjectOfStudent> subjectOfStudentsInSemester = subjectOfStudentRepository
                .findAllBySemesterOfStudent_Id(semesterOfStudent.getId())
                .orElseThrow(() -> new BaseErrorException("Not found any subject of student in this semester", null));

        for (SubjectOfStudent item : subjectOfStudentsInSemester) {
            totalCredit += item.getSubject().getCredit();
            gpaInSemester += item.getGpa() * item.getSubject().getCredit();
        }

        semesterOfStudent.setGpa(gpaInSemester / totalCredit);
        semesterOfStudentRepository.save(semesterOfStudent);

        List<SubjectOfStudent> subjectOfStudents = subjectOfStudentRepository
                .findAllByStudent_StudentCode(student.getStudentCode())
                .orElseThrow(() -> new BaseErrorException("Not found any subject of student", null));

        totalCredit = 0;
        double cpa = 0.0;
        for (SubjectOfStudent item : subjectOfStudents) {
            totalCredit += item.getSubject().getCredit();
            cpa += item.getGpa() * item.getSubject().getCredit();
        }

        student.setCpa(cpa / totalCredit);

        studentRepository.save(student);

        return new BaseResponse("Done", student);
    }

    @Override
    public BaseResponse getSemesterOfStudent() {
        Account account = getCurrentAccount();
        String studentCode = account.getStudent().getStudentCode();
        return new BaseResponse("Done",
                semesterOfStudentRepository.findByStudent_StudentCodeOrderBySemester_yearAscSemester_name(studentCode)
                .orElseThrow(() -> new BaseErrorException("Not found any semester of student: " + studentCode, null)));
//                .stream().map(SemesterOfStudent::getSemester)
//                .collect(Collectors.toList()));
    }

    @Override
    public BaseResponse getSubjectInSemesterOfStudent(String semesterOfStudentId) {
        return new BaseResponse("Done", subjectOfStudentRepository
                .findAllBySemesterOfStudent_Id(semesterOfStudentId)
                .orElseThrow(() -> new BaseErrorException("Not found any subject of student in this semester", null))
                .stream().map(e -> SubjectResponse.builder()
                        .id(e.getId())
                        .gpa(e.getGpa())
                        .finalPoint(e.getFinalPoint())
                        .subject(e.getSubject())
                        .letterGrade(e.getLetterGrade())
                        .attendancePoint(e.getAttendancePoint())
                        .examPoint(e.getExamPoint())
                        .practicePoint(e.getPracticePoint())
                        .testPoint(e.getTestPoint())
                        .build()));
    }

    @Override
    public BaseResponse getSemesterOfStudentById(String semesterStudentId) {
        return new BaseResponse("Done",
                semesterOfStudentRepository.findById(semesterStudentId)
                .orElseThrow(() -> new BaseErrorException("Not found semester with id: " + semesterStudentId, null)));
    }

    @Override
    public BaseResponse getSubjectWarning() {
        Account account = getCurrentAccount();
        String studentCode = account.getStudent().getStudentCode();
        return new BaseResponse("Done", subjectOfStudentRepository
                .findAllByStudent_StudentCode(studentCode)
                .orElseThrow(() -> new BaseErrorException("Not found any subject of student in this semester", null))
                .stream()
                .filter(e -> e.getFinalPoint() <= 4.9)
                .map(e -> SubjectResponse.builder()
                        .id(e.getId())
                        .gpa(e.getGpa())
                        .finalPoint(e.getFinalPoint())
                        .subject(e.getSubject())
                        .letterGrade(e.getLetterGrade())
                        .attendancePoint(e.getAttendancePoint())
                        .examPoint(e.getExamPoint())
                        .practicePoint(e.getPracticePoint())
                        .testPoint(e.getTestPoint())
                        .build()));
    }

    private String parseToLetterGrade(double finalPoint) {
        finalPoint = Math.round(finalPoint * 10.0) / 10.0;
        if (finalPoint >= 9 && finalPoint <= 10)
            return "A+";
        if (finalPoint >= 8.5 && finalPoint <= 8.9)
            return "A";
        if (finalPoint >= 8.0 && finalPoint <= 8.4)
            return "B+";
        if (finalPoint >= 7.0 && finalPoint <= 7.9)
            return "B";
        if (finalPoint >= 6.5 && finalPoint <= 6.9)
            return "C+";
        if (finalPoint >= 5.5 && finalPoint <= 6.4)
            return "C";
        if (finalPoint >= 5.0 && finalPoint <= 5.4)
            return "D+";
        if (finalPoint >= 4.0 && finalPoint <= 4.9)
            return "D";
        return "F";
    }

    private double parseToGpa(String letterGrade) {
        if (letterGrade.equalsIgnoreCase("A+"))
            return 4.0;
        if (letterGrade.equalsIgnoreCase("A"))
            return 3.7;
        if (letterGrade.equalsIgnoreCase("B+"))
            return 3.5;
        if (letterGrade.equalsIgnoreCase("B"))
            return 3.0;
        if (letterGrade.equalsIgnoreCase("C+"))
            return 2.5;
        if (letterGrade.equalsIgnoreCase("C"))
            return 2.0;
        if (letterGrade.equalsIgnoreCase("D+"))
            return 1.5;
        if (letterGrade.equalsIgnoreCase("D"))
            return 1.0;
        return 0.0;
    }

    private Account getCurrentAccount (){
        HttpServletRequest request = ((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest();
        String headerAuth = request.getHeader("Authorization");
        String jwtToken = StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ") ?
                headerAuth.substring(7) : null;

        return accountRepository.findByUsername(jwtUtils.getUsernameFromToken(jwtToken))
                .orElseThrow(() -> new BaseErrorException("Your session is expiated", null));

//        User user = (User) SecurityContextHolder.getContext().getAuthentication().getDetails();
//        return accountRepository.findByUsername(user.getUsername()).get();
    }
}
