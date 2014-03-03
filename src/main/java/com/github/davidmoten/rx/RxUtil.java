package com.github.davidmoten.rx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.functions.Func1;

public class RxUtil {

	private static final Logger log = LoggerFactory.getLogger(RxUtil.class);

	public static final <T> Func1<T, T> println() {
		return new Func1<T, T>() {
			@Override
			public T call(T t) {
				System.out.println(t);
				return t;
			}
		};
	}

	/**
	 * The first sequence will be emitted in its entirety and ignored before o2
	 * starts emitting.
	 * 
	 * @param o1
	 * @param o2
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <T> Observable<T> concatButIgnoreFirstSequence(
			Observable<?> o1, Observable<T> o2) {
		return Observable.concat((Observable<T>) o1.ignoreElements(), o2);
	}

	/**
	 * Logs errors and onNext at info level using slf4j {@link Logger}.
	 * 
	 * @return
	 */
	public static <T> Observer<? super T> log() {
		return new Observer<T>() {

			@Override
			public void onCompleted() {
				// do nothing
			}

			@Override
			public void onError(Throwable e) {
				log.error(e.getMessage(), e);
			}

			@Override
			public void onNext(T t) {
				log.info(t + "");
			}
		};
	}

	/**
	 * Returns a constant value.
	 * 
	 * @param s
	 * @return
	 */
	public static <R, S> Func1<R, S> constant(final S s) {
		return new Func1<R, S>() {
			@Override
			public S call(R t1) {
				return s;
			}
		};
	}

}