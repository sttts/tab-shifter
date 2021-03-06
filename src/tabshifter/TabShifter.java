package tabshifter;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Condition;
import org.jetbrains.annotations.Nullable;
import tabshifter.Directions.Direction;
import tabshifter.valueobjects.LayoutElement;
import tabshifter.valueobjects.Position;
import tabshifter.valueobjects.Split;
import tabshifter.valueobjects.Window;

import static com.intellij.util.containers.ContainerUtil.find;
import static tabshifter.valueobjects.Split.Orientation.horizontal;
import static tabshifter.valueobjects.Split.Orientation.vertical;
import static tabshifter.valueobjects.Split.allSplitsIn;
import static tabshifter.valueobjects.Window.allWindowsIn;

public class TabShifter {
	public static final TabShifter none = new TabShifter(null) {
		@Override public void moveFocus(Direction direction) { }
		@Override public void moveTab(Direction direction) { }
		@Override public void stretchSplitter(Direction direction) { }
	};
	private static final Logger logger = Logger.getInstance(TabShifter.class.getName());

	private final Ide ide;


	public TabShifter(Ide ide) {
		this.ide = ide;
	}

	/**
	 * Potentially this mysterious component com.intellij.ui.switcher.SwitchManagerAppComponent
	 * could be used for switching focus, but it's currently doesn't work very well and is not enabled.
	 */
	public void moveFocus(Direction direction) {
		LayoutElement layout = calculateAndSetPositions(ide.snapshotWindowLayout());
		if (layout == LayoutElement.none) return;

		Window window = currentWindowIn(layout);
		if (window == null) return;

		Window targetWindow = direction.findTargetWindow(window, layout);
		if (targetWindow == null) return;

		ide.setFocusOn(targetWindow);
	}

	/**
	 * Moves tab in the specified direction.
     *
	 * This is way more complicated than it should have been. The main reasons are:
	 * - closing/opening or opening/closing tab doesn't guarantee that focus will be in the moved tab
	 * => need to track target window to move focus into it
	 * - EditorWindow object changes its identity after split/unsplit (i.e. points to another visual window)
	 * => need to predict target window position and look up window by expected position
	 */
	public void moveTab(Direction direction) {
		LayoutElement layout = calculateAndSetPositions(ide.snapshotWindowLayout());
		if (layout == LayoutElement.none) return;
		Window window = currentWindowIn(layout);
		if (window == null) return;

		Window targetWindow = direction.findTargetWindow(window, layout);

		Position newPosition;

		boolean isAtEdge = (targetWindow == null);
		if (isAtEdge) {
			if (window.hasOneTab || !direction.canExpand()) return;

			LayoutElement newLayout = insertSplit(direction.splitOrientation(), window, layout);
			calculateAndSetPositions(newLayout);
			LayoutElement sibling = findSiblingOf(window, newLayout);
			if (sibling == null) return; // should never happen
			newPosition = sibling.position;

			ide.createSplitter(direction.splitOrientation());
			ide.closeCurrentFileIn(window);

		} else {
			boolean willBeUnsplit = window.hasOneTab;
			if (willBeUnsplit) {
				LayoutElement unsplitLayout = removeFrom(layout, window);
				calculateAndSetPositions(unsplitLayout);
			}
			newPosition = targetWindow.position;

			ide.openCurrentFileIn(targetWindow);
			ide.closeCurrentFileIn(window);
		}

		LayoutElement newWindowLayout = calculateAndSetPositions(ide.snapshotWindowLayout());
		targetWindow = findWindowBy(newPosition, newWindowLayout);

		if (targetWindow == null) {
			// ideally this should never happen, logging in case something goes wrong
			logger.warn("No window for: " + newPosition + "; windowLayout: " + newWindowLayout);
		} else {
			ide.setFocusOn(targetWindow);
		}
	}

	public void stretchSplitter(Direction direction) {
		LayoutElement layout = calculateAndSetPositions(ide.snapshotWindowLayout());
		if (layout == LayoutElement.none) return;

		Window window = currentWindowIn(layout);
		if (window == null) return;

		Split split = findParentSplitOf(window, layout);
		Split.Orientation orientationToSkip = (direction == Directions.left || direction == Directions.right) ? horizontal : vertical;
		while (split != null && split.orientation == orientationToSkip) {
			split = findParentSplitOf(split, layout);
		}
		if (split == null) return;

		if (direction == Directions.right || direction == Directions.down) {
			ide.growSplitProportion(split);
		} else {
			ide.shrinkSplitProportion(split);
		}
	}

	@Nullable private static Split findParentSplitOf(LayoutElement layoutElement, LayoutElement layout) {
		for (Split split : allSplitsIn(layout)) {
			if (split.first.equals(layoutElement) || split.second.equals(layoutElement)) {
				return split;
			}
		}
		return null;
	}

	@Nullable private static Window currentWindowIn(LayoutElement windowLayout) {
		return find(allWindowsIn(windowLayout), new Condition<Window>() {
			@Override public boolean value(Window window) {
				return window.isCurrent;
			}
		});
	}

	private static LayoutElement findSiblingOf(Window window, LayoutElement element) {
		if (element instanceof Split) {
			Split split = (Split) element;

			if (split.first.equals(window)) return split.second;
			if (split.second.equals(window)) return split.first;

			LayoutElement first = findSiblingOf(window, split.first);
			if (first != null) return first;

			LayoutElement second = findSiblingOf(window, split.second);
			if (second != null) return second;

			return null;

		} else if (element instanceof Window) {
			return null;

		} else {
			throw new IllegalStateException();
		}
	}

	private static LayoutElement calculateAndSetPositions(LayoutElement element) {
		return calculateAndSetPositions(element, new Position(0, 0, element.size().width, element.size().height));
	}

	private static LayoutElement calculateAndSetPositions(LayoutElement element, Position position) {
		if (element instanceof Split) {
			Split split = (Split) element;

			Position firstPosition;
			Position secondPosition;
			if (split.orientation == vertical) {
				firstPosition = position.withToX(position.toX - split.second.size().width);
				secondPosition = position.withFromX(position.fromX + split.first.size().width);
			} else {
				firstPosition = position.withToY(position.toY - split.second.size().height);
				secondPosition = position.withFromY(position.fromY + split.first.size().height);
			}
			calculateAndSetPositions(split.first, firstPosition);
			calculateAndSetPositions(split.second, secondPosition);
		}

		element.position = position;
		return element;
	}

	@Nullable private static Window findWindowBy(final Position position, LayoutElement layout) {
		return find(allWindowsIn(layout), new Condition<Window>() {
			@Override
			public boolean value(Window window) {
				return position.equals(window.position);
			}
		});
	}

	private static LayoutElement removeFrom(LayoutElement element, Window window) {
		if (element instanceof Split) {
			Split split = (Split) element;
			LayoutElement first = removeFrom(split.first, window);
			LayoutElement second = removeFrom(split.second, window);

			if (first == null) return second;
			else if (second == null) return first;
			else return new Split(first, second, split.orientation);

		} else if (element instanceof Window) {
			return element.equals(window) ? null : element;

		} else {
			throw new IllegalStateException();
		}
	}

	private static LayoutElement insertSplit(Split.Orientation orientation, Window window, LayoutElement element) {
		if (element instanceof Split) {
			Split split = (Split) element;
			return new Split(
					insertSplit(orientation, window, split.first),
					insertSplit(orientation, window, split.second),
					split.orientation
			);
		} else if (element instanceof Window) {
			if (element.equals(window)) {
				return new Split(window, new Window(true, false), orientation);
			} else {
				return element;
			}
		} else {
			throw new IllegalStateException();
		}
	}
}
