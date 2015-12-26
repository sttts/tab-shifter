import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import liveplugin.testrunner.IntegrationTestsRunner
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.junit.Test
import tabshifter.Actions
import tabshifter.Ide
import tabshifter.valueobjects.LayoutElement
import tabshifter.valueobjects.Split
import tabshifter.valueobjects.Window

import java.util.concurrent.atomic.AtomicReference

import static liveplugin.PluginUtil.*
import static org.hamcrest.MatcherAssert.assertThat
import static tabshifter.valueobjects.Split.Orientation.horizontal
import static tabshifter.valueobjects.Split.Orientation.vertical
// add-to-classpath $HOME/Library/Application Support/IntelliJIdea15/live-plugins/tab-shift/out/production/tab-shifter/

IntegrationTestsRunner.runIntegrationTests([TabShifterIntegrationTest], project)

class TabShifterIntegrationTest {

	@Test void "shifting tab right creates new splitter"() {
		assertLayout(window(readmeMd, contributeMd, todoTxt))

		shiftRight(readmeMd)

		assertLayout(split(vertical,
				window(contributeMd, todoTxt), window(readmeMd)
		))
	}

	@Test void "shifting tab right twice results in single splitter"() {
		assertLayout(window(readmeMd, contributeMd, todoTxt))

		shiftRight(readmeMd)
		shiftRight(readmeMd)

		assertLayout(split(vertical,
				window(contributeMd, todoTxt), window(readmeMd)
		))
	}

	@Test void "shifting two tabs right creates only one new splitter"() {
		assertLayout(window(readmeMd, contributeMd, todoTxt))

		shiftRight(readmeMd)
		shiftRight(contributeMd)

		assertLayout(split(vertical,
				window(todoTxt), window(contributeMd, readmeMd)
		))
	}

	@Test void "shifting all tabs right results in single window"() {
		assertLayout(window(readmeMd, contributeMd, todoTxt))

		shiftRight(readmeMd)
		shiftRight(contributeMd)
		shiftRight(todoTxt)

		assertLayout(window(todoTxt, contributeMd, readmeMd))
	}

	@Test void "shifting tab right and left results in single window"() {
		assertLayout(window(readmeMd, contributeMd, todoTxt))

		shiftRight(readmeMd)
		shiftLeft(readmeMd)

		assertLayout(window(readmeMd, contributeMd, todoTxt))
	}

	@Test void "shifting tab right and down results in nested splits"() {
		assertLayout(window(readmeMd, contributeMd, todoTxt))

		shiftRight(readmeMd)
		shiftRight(contributeMd)
		shiftDown(readmeMd)

		assertLayout(split(vertical,
				window(todoTxt),
				split(horizontal,
						window(contributeMd),
						window(readmeMd))
		))
	}

	TabShifterIntegrationTest() {
		invokeOnEDT {
			project = openProject(System.properties["user.home"] + "/IdeaProjects/junit")
			editorManager = FileEditorManagerEx.getInstanceEx(project)
			readmeMd = findFileByName("README.md", project).virtualFile
			contributeMd = findFileByName("CONTRIBUTING.md", project).virtualFile
			todoTxt = findFileByName("to-do.txt", project).virtualFile

			closeAllFiles(editorManager, project)
			[todoTxt, contributeMd, readmeMd].each {
				editorManager.openFile(it, true)
			}
		}
	}

	public void closeAllFiles(FileEditorManagerEx editorManager, Project project) {
		editorManager.windows.each { window ->
			window.files.each { window.closeFile(it) }
		}
		ide = new Ide(editorManager, project)
		assert ide.snapshotWindowLayout() == LayoutElement.none
	}

	private static Matcher split(Split.Orientation orientation, Matcher firstMatcher, Matcher secondMatcher) {
		new BaseMatcher() {
			@Override boolean matches(Object o) {
				o instanceof Split && o.orientation == orientation &&
					firstMatcher.matches(o.first) && secondMatcher.matches(o.second)
			}

			@Override void describeTo(Description description) {
				description.appendText("Split(${orientation} ")
				description.appendList("", ",", "", [firstMatcher, secondMatcher])
			}
		}
	}

	private static Matcher window(VirtualFile... files) {
		new BaseMatcher() {
			@Override boolean matches(Object o) {
				o instanceof Window && o.editorWindow.files.toList().toSet() == files.toList().toSet()
			}

			@Override void describeTo(Description description) {
				description.appendText("Window(" + files.collect{it.name}.join(",") + ")")
			}
		}
	}

	private assertLayout(Matcher matcher) {
		def error = new AtomicReference<Throwable>()
		def exception = new AtomicReference<Throwable>()
		invokeOnEDT {
			try {
				def layout = ide.snapshotWindowLayout()
				assertThat(layout, matcher)
			} catch (AssertionError e) {
				error.set(e)
			} catch (Exception e) {
				exception.set(e)
			}
		}
		if (error.get() != null) {
			throw new AssertionError(error.get())
		}
		if (exception.get() != null) {
			throw new Exception(exception.get())
		}
	}

	private shiftRight(VirtualFile file) {
		shift(file, new Actions.ShiftRight())
	}

	private shiftLeft(VirtualFile file) {
		shift(file, new Actions.ShiftLeft())
	}

	private shiftDown(VirtualFile file) {
		shift(file, new Actions.ShiftDown())
	}

	private shift(VirtualFile file, AnAction shiftAction) {
		invokeOnEDT {
			def layout = ide.snapshotWindowLayout()
			def window = findWindowWith(file, layout)
			ide.setFocusOn(window)
			editorManager.openFile(file, true)
			shiftAction.actionPerformed(anActionEvent(newDataContext(["project": project])))
		}
	}

	private static Window findWindowWith(VirtualFile file, LayoutElement layoutElement) {
		if (layoutElement instanceof Window) {
			def foundFile = layoutElement.editorWindow.files.find{ it == file}
			foundFile != null ? layoutElement : null
		} else if (layoutElement instanceof Split) {
			def result = findWindowWith(file, layoutElement.first)
			if (result == null) result = findWindowWith(file, layoutElement.second)
			result
		} else {
			throw new IllegalStateException()
		}
	}


	private project
	private Ide ide
	private FileEditorManagerEx editorManager
	private VirtualFile readmeMd
	private VirtualFile todoTxt
	private VirtualFile contributeMd
}