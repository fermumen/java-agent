package dev.fxjava;

import java.io.IOException;

/**
 * Raw-shell presentation contract for ask_user_question: renders one
 * FX-shaped inline panel per question and returns the chosen option label,
 * or null when the user cancelled or input ended.
 */
interface QuestionFlow {
    String ask(AskUserTool.Question question) throws IOException;
}
