package andel;

import andel.intervals.ListIterator;
import andel.intervals.MergingIterator;

import java.util.Comparator;
import java.util.List;

public interface IntervalsIterator<T> {

  Comparator<IntervalsIterator> FORWARD_COMPARATOR = Comparator.comparingLong(IntervalsIterator::from);
  Comparator<IntervalsIterator> BACKWARD_COMPARATOR = FORWARD_COMPARATOR.reversed();

  boolean closedLeft();

  boolean closedRight();

  long from();

  long to();

  long id();

  T data();

  boolean next();

  static <T> IntervalsIterator<T> fromList(List<Interval<T>> list) {
    return new ListIterator<>(list);
  }

  static <T> IntervalsIterator<T> merge(IntervalsIterator<T> it1, IntervalsIterator<T> it2, Comparator<IntervalsIterator> comparator) {
    return new MergingIterator<>(it1, it2, comparator);
  }

  default Interval<T> interval() {
    return new Interval<>(this.id(), this.from(), this.to(), this.closedLeft(), this.closedRight(), this.data());
  }
}
