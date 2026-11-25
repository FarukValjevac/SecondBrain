-- Answer ratings, flashcards and quizzes were removed from the application (issues #105, #118).
-- Their tables still held private questions, answers and generated study text that nothing could
-- show or delete any more. Dropping them removes that data for good.
DROP TABLE IF EXISTS answer_feedback_sources;
DROP TABLE IF EXISTS answer_feedback;
DROP FUNCTION IF EXISTS remove_answer_feedback_without_source();
DROP TABLE IF EXISTS quiz_questions;
DROP TABLE IF EXISTS quizzes;
DROP FUNCTION IF EXISTS remove_quiz_without_document();
DROP TABLE IF EXISTS flashcards;
