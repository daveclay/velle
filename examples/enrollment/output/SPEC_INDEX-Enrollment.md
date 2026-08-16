# Spec Index — Enrollment

Generated from the Velle spec (testgen.md). One file per business state;
each case below is an executable test.

## SetEnrollmentSpec.kt

- ApplyEnrollmentSet - a new SetEnrollment sets student.courses

## EnrollSpec.kt

- ApplyEnroll - a new Enroll sets student.courses

## DropSpec.kt

- ApplyDrop - a new Drop sets student.courses

## TagSpec.kt

- ApplyTag - a new Tag sets student.tags

## AssignAdvisorSpec.kt

- ApplyAdvisor - a new AssignAdvisor sets students.advisor

## CourseSpec.kt

- never - a Course where capacity below 0 is refused
- never - a Course with capacity 0 is accepted
