package org.batfish.question;

import org.batfish.common.Answerer;
import org.batfish.common.Directory;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.Question;

public class FileTreeQuestionPlugin extends QuestionPlugin {

  public static class FileTreeAnswerElement implements AnswerElement {

    Directory _testRigRoot;

    public Directory getTestRigRoot() {
      return _testRigRoot;
    }

    public void setTestRigRoot(Directory testRigRoot) {
      _testRigRoot = testRigRoot;
    }
  }

  public static class FileTreeAnswerer extends Answerer {

    public FileTreeAnswerer(Question question, IBatfish batfish) {
      super(question, batfish);
    }

    @Override
    public FileTreeAnswerElement answer() {
      Directory root = _batfish.getTestrigFileTree();
      FileTreeAnswerElement ae = new FileTreeAnswerElement();
      ae.setTestRigRoot(root);
      return ae;
    }
  }

  // <question_page_comment>

  /**
   * Outputs file tree of test-rig directory
   *
   * @type FileTree multifile
   * @example bf_answer("filetree")
   */
  public static class FileTreeQuestion extends Question {

    public FileTreeQuestion() {}

    @Override
    public boolean getDataPlane() {
      return false;
    }

    @Override
    public String getName() {
      return "filetree";
    }

    @Override
    public boolean getTraffic() {
      return false;
    }

    @Override
    public String prettyPrint() {
      return getName();
    }
  }

  @Override
  protected FileTreeAnswerer createAnswerer(Question question, IBatfish batfish) {
    return new FileTreeAnswerer(question, batfish);
  }

  @Override
  protected FileTreeQuestion createQuestion() {
    return new FileTreeQuestion();
  }
}
