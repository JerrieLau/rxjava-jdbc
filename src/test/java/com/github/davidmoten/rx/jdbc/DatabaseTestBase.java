package com.github.davidmoten.rx.jdbc;

import static com.github.davidmoten.rx.RxUtil.constant;
import static com.github.davidmoten.rx.RxUtil.log;
import static com.github.davidmoten.rx.RxUtil.toEmpty;
import static com.github.davidmoten.rx.jdbc.DatabaseCreator.connectionProvider;
import static com.github.davidmoten.rx.jdbc.DatabaseCreator.createDatabase;
import static com.github.davidmoten.rx.jdbc.DatabaseCreator.nextUrl;
import static java.util.Arrays.asList;
import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static rx.Observable.from;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.easymock.EasyMock;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.Operator;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.observables.MathObservable;

import com.github.davidmoten.rx.RxUtil;
import com.github.davidmoten.rx.UnsubscribeDetector;
import com.github.davidmoten.rx.jdbc.tuple.Tuple2;
import com.github.davidmoten.rx.jdbc.tuple.Tuple3;
import com.github.davidmoten.rx.jdbc.tuple.Tuple4;
import com.github.davidmoten.rx.jdbc.tuple.Tuple5;
import com.github.davidmoten.rx.jdbc.tuple.Tuple6;
import com.github.davidmoten.rx.jdbc.tuple.Tuple7;
import com.github.davidmoten.rx.jdbc.tuple.TupleN;
import com.mchange.v2.c3p0.ComboPooledDataSource;

public abstract class DatabaseTestBase {

	/**
	 * <p>
	 * Timeout used for latch.await() and similar. If too short then short
	 * machine lockups on build server (which is known to happen on CloudBees
	 * Jenkins infrastructure) will cause undesired test failures.
	 * </p>
	 * 
	 * <p>
	 * While your test is still failing use a lower value of course so that you
	 * get rapid turnaround. However, once passing switch the timeout to this
	 * standard timeout value.
	 * </p>
	 */
	private static final int TIMEOUT_SECONDS = 3;

	private static final Logger log = LoggerFactory
			.getLogger(DatabaseTestBase.class);

	private final boolean async;

	public DatabaseTestBase(boolean async) {
		this.async = async;
	}

	Database db() {
		if (async)
			return DatabaseCreator.db().asynchronous();
		else
			return DatabaseCreator.db();
	}

	@Test
	public void testSimpleExample() {
		Observable<String> names = db().select(
				"select name from person order by name").getAs(String.class);
		// convert the names to a list for unit test
		List<String> list = names.toList().toBlockingObservable().single();
		log.debug("list=" + list);
		assertEquals(asList("FRED", "JOSEPH", "MARMADUKE"), list);
	}

	@Test
	public void testCountQuery() {
		int count = db()
		// select names
				.select("select name from person where name >?")
				// set name parameter
				.parameter("ALEX")
				// count results
				.count()
				// get count
				.first()
				// block till finished
				.toBlockingObservable().single();
		assertEquals(3, count);
	}

	@Test
	public void testTransactionUsingCount() {
		Database db = db();
		Func1<? super Integer, Boolean> isZero = new Func1<Integer, Boolean>() {
			@Override
			public Boolean call(Integer t1) {
				return t1 == 0;
			}
		};
		Observable<Integer> existingRows = db
		// select names
				.select("select name from person where name=?")
				// set name parameter
				.parameter("FRED")
				// is part of transaction
				.dependsOn(db.beginTransaction())
				// get result count
				.count()
				// return empty if count = 0
				.filter(isZero);
		Observable<Integer> update = db
		// insert record if does not exist
				.update("insert into person(name,score) values(?,0)")
				// get parameters from last query
				.parameters(existingRows.map(constant("FRED")))
				// return num rows affected
				.count();

		boolean committed = db.commit(update).toBlockingObservable().single();
		assertTrue(committed);
	}

	@Test
	public void testTransactionOnCommit() {
		Database db = db();
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> updateCount = db
		// set everyones score to 99
				.update("update person set score=?")
				// is within transaction
				.dependsOn(begin)
				// new score
				.parameter(99)
				// execute
				.count();
		Observable<Boolean> commit = db.commit(updateCount);
		long count = db.select("select count(*) from person where score=?")
		// set score
				.parameter(99)
				// depends on
				.dependsOn(commit)
				// return as Long
				.getAs(Long.class)
				// log
				.doOnEach(RxUtil.log())
				// get answer
				.toBlockingObservable().single();
		assertEquals(3, count);
	}

	@Test
	public void testPushEmptyList() {
		Database db = db();
		Observable<Integer> rowsAffected = Observable
		// generate two integers
				.range(1, 2)
				// replace the integers with empty lists
				.map(toEmpty())
				// execute the update
				.lift(db.update("update person set score = score + 1")
						.parameterListOperator())
				// flatten
				.lift(RxUtil.<Integer> flatten())
				// total the affected records
				.lift(SUM_INTEGER);
		assertIs(6, rowsAffected);
	}

	@Test
	public void testRunScript() {
		Observable<String> commands = from(asList(
				"create table temp1(id integer)", "drop table temp1"));
		db().run(commands).count().toBlockingObservable().single();
	}

	@Test
	public void testTransactionOnCommitDoesntOccurUnlessSubscribedTo() {
		Database db = db();
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> u = db.update("update person set score=?")
				.dependsOn(begin).parameter(99).count();
		db.commit(u);
		// note that last transaction was not listed as a dependency of the next
		// query
		long count = db.select("select count(*) from person where score=?")
				.parameter(99).getAs(Long.class).toBlockingObservable()
				.single();
		assertEquals(0, count);
	}

	@Test
	public void testTransactionOnRollback() {
		Database db = db();
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> updateCount = db
				.update("update person set score=?").dependsOn(begin)
				.parameter(99).count();
		db.rollback(updateCount);
		long count = db.select("select count(*) from person where score=?")
				.parameter(99).dependsOnLastTransaction().getAs(Long.class)
				.toBlockingObservable().single();
		assertEquals(0, count);
	}

	@Test
	public void testUpdateAndSelectWithTransaction() {
		Database db = db();
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> updateCount = db
		// update everyone's score to 99
				.update("update person set score=?")
				// in transaction
				.dependsOn(begin)
				// new score
				.parameter(99)
				// execute
				.count();
		long count = db.select("select count(*) from person where score=?")
		// where score = 99
				.parameter(99)
				// depends on
				.dependsOn(updateCount)
				// as long value
				.getAs(Long.class).toBlockingObservable().single();

		assertEquals(3, count);
	}

	@Test
	public void testUseParameterObservable() {
		int count = db().select("select name from person where name >?")
				.parameters(Observable.from("ALEX")).count()
				.toBlockingObservable().single();
		assertEquals(3, count);
	}

	@Test
	public void testTwoParameters() {
		List<String> list = db()
				.select("select name from person where name > ? and name < ?")
				.parameter("ALEX").parameter("LOUIS").getAs(String.class)
				.toList().toBlockingObservable().single();
		assertEquals(asList("FRED", "JOSEPH"), list);
	}

	@Test
	public void testTakeFewerThanAvailable() {
		int count = db().select("select name from person where name >?")
				.parameter("ALEX").get(RxUtil.<ResultSet, Integer> constant(1))
				.take(2).count().first().toBlockingObservable().single();
		assertEquals(2, count);
	}

	@Test
	public void testJdbcObservableCountLettersInAllNames() {
		int count = MathObservable.sumInteger(db()
		// select
				.select("select name from person where name >?")
				// set name
				.parameter("ALEX")
				// count letters
				.get(COUNT_LETTERS_IN_NAME))
		// first result
				.first()
				// block and get result
				.toBlockingObservable().single();
		assertEquals(19, count);
	}

	private static final Func1<ResultSet, Integer> COUNT_LETTERS_IN_NAME = new Func1<ResultSet, Integer>() {
		@Override
		public Integer call(ResultSet rs) {
			try {
				return rs.getString("name").length();
			} catch (SQLException e) {
				throw new RuntimeException(e);
			}
		}
	};

	@Test
	public void testTransformToTuple2AndTestActionsPrintln() {
		Tuple2<String, Integer> tuple = db()
				.select("select name,score from person where name >? order by name")
				.parameter("ALEX").getAs(String.class, Integer.class).last()
				.toBlockingObservable().single();
		assertEquals("MARMADUKE", tuple.value1());
		assertEquals(25, (int) tuple.value2());
	}

	@Test
	public void testTransformToTupleN() {
		TupleN<String> tuple = db()
				.select("select name, lower(name) from person order by name")
				.getTupleN(String.class).first().toBlockingObservable()
				.single();
		assertEquals("FRED", tuple.values().get(0));
		assertEquals("fred", tuple.values().get(1));
	}

	@Test
	public void testMultipleSetsOfParameters() {
		List<Integer> list = db()
				.select("select score from person where name=?")
				// first param
				.parameter("FRED")
				// second param
				.parameter("JOSEPH")
				// score as integer
				.getAs(Integer.class)
				// log
				.doOnEach(log())
				// sort
				.toSortedList()
				// block and get
				.toBlockingObservable().single();
		assertEquals(asList(21, 34), list);
	}

	@Test
	public void testNoParams() {
		List<Tuple2<String, Integer>> tuples = db()
				.select("select name, score from person where name=? order by name")
				.getAs(String.class, Integer.class).toList()
				.toBlockingObservable().single();
		assertEquals(0, tuples.size());
	}

	@Test
	public void testCreateFromScript() {
		Database db = Database.from(DatabaseCreator.nextUrl());
		Observable<Integer> create = db.run(DatabaseTestBase.class
				.getResourceAsStream("/db-creation-script.sql"), ";");
		Observable<Integer> count = db.select("select name from person")
				.dependsOn(create).getAs(String.class).count();
		assertIs(3, count);
	}

	@Test
	public void testComposition2() {
		log.debug("running testComposition2");
		Func1<Integer, Boolean> isZero = new Func1<Integer, Boolean>() {
			@Override
			public Boolean call(Integer count) {
				return count == 0;
			}
		};
		Database db = db();
		Observable<Integer> existingRows = db
				.select("select name from person where name=?")
				.parameter("FRED").getAs(String.class).count().filter(isZero);
		List<Integer> counts = db
				.update("insert into person(name,score) values(?,?)")
				.parameters(existingRows).count().toList()
				.toBlockingObservable().single();
		assertEquals(0, counts.size());
	}

	@Test
	public void testEmptyResultSet() {
		int count = db().select("select name from person where name >?")
				.parameters(Observable.from("ZZTOP")).count().first()
				.toBlockingObservable().single();
		assertEquals(0, count);
	}

	@Test
	public void testMixingExplicitAndObservableParameters() {
		String name = db()
				.select("select name from person where name > ?  and score < ? order by name")
				.parameter("BARRY").parameters(Observable.from(100))
				.getAs(String.class).first().toBlockingObservable().single();
		assertEquals("FRED", name);
	}

	@Test
	public void testInstantiateDatabaseWithUrl() throws SQLException {
		Database db = Database.from("jdbc:h2:mem:testa1");
		Connection con = db.queryContext().connectionProvider().get();
		con.close();
	}

	@Test
	public void testComposition() {
		// use composition to find the first person alphabetically with
		// a score less than the person with the last name alphabetically
		// whose name is not XAVIER. Two threads and connections will be used.

		Database db = db();
		Observable<Integer> score = db
				.select("select score from person where name <> ? order by name")
				.parameter("XAVIER").getAs(Integer.class).last();
		Observable<String> name = db
				.select("select name from person where score < ? order by name")
				.parameters(score).getAs(String.class).first();
		assertIs("FRED", name);
	}

	@Test
	public void testCompositionUsingLift() {
		// use composition to find the first person alphabetically with
		// a score less than the person with the last name alphabetically
		// whose name is not XAVIER. Two threads and connections will be used.

		Database db = db();
		Observable<String> name = db
				.select("select score from person where name <> ? order by name")
				.parameter("XAVIER")
				.getAs(Integer.class)
				.last()
				.lift(db.select(
						"select name from person where score < ? order by name")
						.parameterOperator().getAs(String.class)).first();
		assertIs("FRED", name);
	}

	@Test
	public void testCompositionTwoLevels() {

		Database db = db();
		Observable<String> names = db.select(
				"select name from person order by name").getAs(String.class);
		Observable<String> names2 = db
				.select("select name from person where name<>? order by name")
				.parameters(names).parameters(names).getAs(String.class);
		List<String> list = db.select("select name from person where name>?")
				.parameters(names2).getAs(String.class).toList()
				.toBlockingObservable().single();
		System.out.println(list);
		assertEquals(12, list.size());
	}

	@Test(expected = RuntimeException.class)
	public void testSqlProblem() {
		String name = db().select("select name from pperson where name >?")
				.parameter("ALEX").getAs(String.class).first()
				.toBlockingObservable().single();
		log.debug(name);
	}

	@Test(expected = ClassCastException.class)
	public void testException() {
		Integer name = db().select("select name from person where name >?")
				.parameter("ALEX").getAs(Integer.class).first()
				.toBlockingObservable().single();
		log.debug("name=" + name);
	}

	@Test
	public void testAutoMapWillMapStringToStringAndIntToDouble() {
		Person person = db()
				.select("select name,score,dob,registered from person order by name")
				.autoMap(Person.class).first().toBlockingObservable().single();
		assertEquals("FRED", person.getName());
		assertEquals(21, person.getScore(), 0.001);
		assertNull(person.getDateOfBirth());
	}

	@Test(expected = RuntimeException.class)
	public void testAutoMapCannotFindConstructorWithEnoughParameters() {
		db().select(
				"select name,score,dob,registered,name from person order by name")
				.autoMap(Person.class).first().toBlockingObservable().single();
	}

	@Test
	public void testGetTimestamp() {
		Database db = db();
		java.sql.Timestamp registered = new java.sql.Timestamp(100);
		Observable<Integer> u = db
				.update("update person set registered=? where name=?")
				.parameter(registered).parameter("FRED").count();
		Date regTime = db.select("select registered from person order by name")
				.dependsOn(u).getAs(Date.class).first().toBlockingObservable()
				.single();
		assertEquals(100, regTime.getTime());
	}

	@Test
	public void insertClobAndReadAsString() throws SQLException {
		Database db = db();
		insertClob(db);
		// read clob as string
		String text = db.select("select document from person_clob")
				.getAs(String.class).first().toBlockingObservable().single();
		assertTrue(text.contains("about Fred"));
	}

	private static void insertClob(Database db) {
		Observable<Integer> count = db
				.update("insert into person_clob(name,document) values(?,?)")
				.parameter("FRED")
				.parameter(
						"A description about Fred that is rather long and needs a Clob to store it")
				.count();
		assertIs(1, count);
	}

	private static void insertBlob(Database db) {
		Observable<Integer> count = db
				.update("insert into person_blob(name,document) values(?,?)")
				.parameter("FRED")
				.parameter(
						"A description about Fred that is rather long and needs a Clob to store it"
								.getBytes()).count();
		assertIs(1, count);
	}

	@Test
	public void insertClobAndReadAsReader() throws SQLException, IOException {
		Database db = db();
		insertClob(db);
		// read clob as Reader
		String text = db.select("select document from person_clob")
				.getAs(Reader.class).map(Util.READER_TO_STRING).first()
				.toBlockingObservable().single();
		assertTrue(text.contains("about Fred"));
	}

	@Test
	public void insertBlobAndReadAsByteArray() throws SQLException {
		Database db = db();
		insertBlob(db);
		// read clob as string
		byte[] bytes = db.select("select document from person_blob")
				.getAs(byte[].class).first().toBlockingObservable().single();
		assertTrue(new String(bytes).contains("about Fred"));
	}

	@Test
	public void testInsertNull() {
		Observable<Integer> count = db()
				.update("insert into person(name,score,dob) values(?,?,?)")
				.parameters("JACK", 42, null).count();
		assertIs(1, count);
	}

	@Test
	public void testAutoMap() {
		TimeZone current = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("AEST"));
			Database db = db();
			Date dob = new Date(100);
			long now = System.currentTimeMillis();
			java.sql.Timestamp registered = new java.sql.Timestamp(now);
			Observable<Integer> u = db
					.update("update person set dob=?, registered=? where name=?")
					.parameter(dob).parameter(registered).parameter("FRED")
					.count();
			Person person = db
					.select("select name,score,dob,registered from person order by name")
					.dependsOn(u).autoMap(Person.class).first()
					.toBlockingObservable().single();
			assertEquals("FRED", person.getName());
			assertEquals(21, person.getScore(), 0.001);
			// Dates are truncated to start of day
			assertEquals(0, (long) person.getDateOfBirth());
			assertEquals(now, (long) person.getRegistered());
		} finally {
			TimeZone.setDefault(current);
		}
	}

	@Test
	public void testLastTransactionWithoutTransaction() {
		assertIs(0, db().lastTransactionResult().count());
	}

	@Test
	public void testTuple3() {
		Tuple3<String, Integer, String> tuple = db()
				.select("select name,1,lower(name) from person order by name")
				.getAs(String.class, Integer.class, String.class).first()
				.toBlockingObservable().single();
		assertEquals("FRED", tuple.value1());
		assertEquals(1, (int) tuple.value2());
		assertEquals("fred", tuple.value3());
	}

	@Test
	public void testTuple4() {
		Tuple4<String, Integer, String, Integer> tuple = db()
				.select("select name,1,lower(name),2 from person order by name")
				.getAs(String.class, Integer.class, String.class, Integer.class)
				.first().toBlockingObservable().single();
		assertEquals("FRED", tuple.value1());
		assertEquals(1, (int) tuple.value2());
		assertEquals("fred", tuple.value3());
		assertEquals(2, (int) tuple.value4());
	}

	@Test
	public void testTuple5() {
		Tuple5<String, Integer, String, Integer, String> tuple = db()
				.select("select name,1,lower(name),2,name from person order by name")
				.getAs(String.class, Integer.class, String.class,
						Integer.class, String.class).first()
				.toBlockingObservable().single();
		assertEquals("FRED", tuple.value1());
		assertEquals(1, (int) tuple.value2());
		assertEquals("fred", tuple.value3());
		assertEquals(2, (int) tuple.value4());
		assertEquals("FRED", tuple.value5());
	}

	@Test
	public void testTuple6() {
		Tuple6<String, Integer, String, Integer, String, Integer> tuple = db()
				.select("select name,1,lower(name),2,name,3 from person order by name")
				.getAs(String.class, Integer.class, String.class,
						Integer.class, String.class, Integer.class).first()
				.toBlockingObservable().single();
		assertEquals("FRED", tuple.value1());
		assertEquals(1, (int) tuple.value2());
		assertEquals("fred", tuple.value3());
		assertEquals(2, (int) tuple.value4());
		assertEquals("FRED", tuple.value5());
		assertEquals(3, (int) tuple.value6());
	}

	@Test
	public void testTuple7() {
		Tuple7<String, Integer, String, Integer, String, Integer, Integer> tuple = db()
				.select("select name,1,lower(name),2,name,3,4 from person order by name")
				.getAs(String.class, Integer.class, String.class,
						Integer.class, String.class, Integer.class,
						Integer.class).first().toBlockingObservable().single();
		assertEquals("FRED", tuple.value1());
		assertEquals(1, (int) tuple.value2());
		assertEquals("fred", tuple.value3());
		assertEquals(2, (int) tuple.value4());
		assertEquals("FRED", tuple.value5());
		assertEquals(3, (int) tuple.value6());
		assertEquals(4, (int) tuple.value7());
	}

	@Test
	public void testAutoMapClob() {
		Database db = db();
		insertClob(db);
		List<PersonClob> list = db
				.select("select name, document from person_clob")
				.autoMap(PersonClob.class).toList().toBlockingObservable()
				.single();
		assertEquals(1, list.size());
		assertEquals("FRED", list.get(0).getName());
		assertTrue(list.get(0).getDocument().contains("rather long"));
	}

	@Test
	public void testAutoMapBlob() {
		Database db = db();
		insertBlob(db);
		List<PersonBlob> list = db
				.select("select name, document from person_blob")
				.autoMap(PersonBlob.class).toList().toBlockingObservable()
				.single();
		assertEquals(1, list.size());
		assertEquals("FRED", list.get(0).getName());
		assertTrue(new String(list.get(0).getDocument())
				.contains("rather long"));
	}

	@Test
	public void testCalendarParameter() throws SQLException {
		Database db = db();
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(0);
		Observable<Integer> update = db
				.update("update person set registered=? where name=?")
				.parameters(cal, "FRED").count();
		Timestamp t = db.select("select registered from person where name=?")
				.parameter("FRED").dependsOn(update).getAs(Timestamp.class)
				.first().toBlockingObservable().single();
		assertEquals(0, t.getTime());
	}

	@Test
	public void testDatabaseBuilder() {
		Database.builder().connectionProvider(connectionProvider())
				.nonTransactionalSchedulerOnCurrentThread().build();
	}

	@Test
	public void testConnectionPool() {
		ConnectionProviderPooled cp = new ConnectionProviderPooled(nextUrl(),
				0, 10);
		Database db = createDatabase(cp);
		int count = db.select("select name from person order by name").count()
				.toBlockingObservable().single();
		assertEquals(3, count);
		cp.close();
		// and again to test idempotentcy
		cp.close();
	}

	@Test(expected = RuntimeException.class)
	public void testConnectionPoolWhenExceptionThrown() throws SQLException {
		ComboPooledDataSource pool = new ComboPooledDataSource();
		pool.setJdbcUrl("invalid");
		pool.setAcquireRetryAttempts(0);
		new ConnectionProviderPooled(pool).get();
	}

	@Test
	public void testConnectionPoolDoesNotRunOutOfConnectionsWhenQueryRunRepeatedly()
			throws SQLException {
		ConnectionProviderPooled cp = new ConnectionProviderPooled(nextUrl(),
				0, 1);
		Database db = new Database(cp);
		Connection con = cp.get();
		DatabaseCreator.createDatabase(con);
		con.close();
		assertIs(100, db.select("select name from person where name=?")
				.parameters(Observable.range(0, 100).map(constant("FRED")))
				.count());
	}

	// TODO add unit test to check that resources closed (connection etc) before
	// onComplete or onError called on either select or update

	@Test
	public void testDatabaseBuilderWithPool() {
		Database.builder().url(nextUrl()).pool(0, 5).build().close();
	}

	@Test
	public void testOneConnectionOpenAndClosedAfterOneSelect()
			throws InterruptedException {
		CountDownConnectionProvider cp = new CountDownConnectionProvider(1, 1);
		Database db = new Database(cp);
		db.select("select name from person").count().toBlockingObservable()
				.single();
		cp.closesLatch().await();
		cp.getsLatch().await();
	}

	@Test
	public void testOneConnectionOpenAndClosedAfterOneUpdate()
			throws InterruptedException {
		CountDownConnectionProvider cp = new CountDownConnectionProvider(1, 1);
		Database db = new Database(cp);
		db.update("update person set score=? where name=?")
				.parameters(23, "FRED").count().toBlockingObservable().single();
		cp.closesLatch().await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		cp.getsLatch().await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	@Test
	public void testLiftWithParameters() {
		int score = from("FRED")
				.lift(db().select("select score from person where name=?")
						.parameterOperator().getAs(Integer.class))
				.toBlockingObservable().single();
		assertEquals(21, score);
	}

	@Test
	public void testLiftWithManyParameters() {
		int score = Observable
		// range
				.range(1, 3)
				// log
				.doOnEach(log())
				// to parameter
				.map(constant("FRED")).lift(db()
				// select
						.select("select score from person where name=?")
						// push parameters
						.parameterOperator()
						// get score as integer
						.getAs(Integer.class))
				// sum values
				.lift(SUM_INTEGER)
				// block and get
				.toBlockingObservable().single();
		assertEquals(3 * 21, score);
	}

	private final Operator<Integer, Integer> SUM_INTEGER = RxUtil
			.toOperator(new Func1<Observable<Integer>, Observable<Integer>>() {
				@Override
				public Observable<Integer> call(Observable<Integer> source) {
					return MathObservable.sumInteger(source);
				}
			});

	@Test
	public void testDetector() throws InterruptedException {
		UnsubscribeDetector<Integer> detector = RxUtil.detectUnsubscribe();
		Observable.range(1, 10).lift(detector).take(1).toBlockingObservable()
				.single();
		assertTrue(detector.latch().await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
	}

	@Test
	public void testUnsubscribeOfBufferAndFlatMap() throws InterruptedException {
		UnsubscribeDetector<Long> detector = RxUtil.detectUnsubscribe();
		Observable.interval(10, TimeUnit.MILLISECONDS).lift(detector).buffer(2)
				.flatMap(constant(Observable.from(1L))).take(6).toList()
				.toBlockingObservable().single();
		assertTrue(detector.latch().await(3, TimeUnit.SECONDS));
	}

	@Test
	public void testParametersAreUnsubscribedIfUnsubscribedPostParameterOperatorLift()
			throws InterruptedException {
		UnsubscribeDetector<String> detector = RxUtil.detectUnsubscribe();
		Observable
				.interval(100, TimeUnit.MILLISECONDS)
				.doOnEach(log())
				.map(constant("FRED"))
				.lift(detector)
				.lift(db().select("select score from person where name=?")
						.parameterOperator().getAs(Integer.class)).take(1)
				.subscribe(log());
		assertTrue(detector.latch().await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
	}

	@Test
	public void testParametersAreUnsubscribed() throws InterruptedException {
		UnsubscribeDetector<String> detector = RxUtil.detectUnsubscribe();
		Observable<String> params = Observable
				.interval(100, TimeUnit.MILLISECONDS).doOnEach(log())
				.map(constant("FRED")).lift(detector);
		db().select("select score from person where name=?").parameters(params)
				.getAs(Integer.class).take(1).subscribe(log());
		assertTrue(detector.latch().await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
	}

	@Test
	public void testLiftSelectWithDependencies() {
		Database db = db();
		Observable<Integer> count = db
				.update("update person set score=? where name=?")
				.parameters(4, "FRED")
				.count()
				.lift(db.select("select score from person where name=?")
						.parameters("FRED").dependsOnOperator()
						.getAs(Integer.class));
		assertIs(4, count);
	}

	@Test
	public void testLiftUpdateWithParameters() {
		Database db = db();
		Observable<Integer> count = Observable.from(
				Arrays.<Object> asList(4, "FRED")).lift(
				db.update("update person set score=? where name=?")
						.parameterOperator());
		assertIs(1, count);
	}

	@Test
	public void testLiftUpdateWithDependencies() {
		Database db = db();
		Observable<Integer> score = Observable
		// parameters for coming update
				.from(Arrays.<Object> asList(4, "FRED"))
				// update Fred's score to 4
				.lift(db.update("update person set score=? where name=?")
						.parameterOperator())
				// update everyone with score of 4 to 14
				.lift(db.update("update person set score=? where score=?")
						.parameters(14, 4).dependsOnOperator())
				// get Fred's score
				.lift(db.select("select score from person where name=?")
						.parameters("FRED").dependsOnOperator()
						.getAs(Integer.class));
		assertIs(14, score);
	}

	static <T> void assertIs(T t, Observable<T> observable) {
		assertEquals(t, observable.toBlockingObservable().single());
	}

	@Test
	public void testTwoConnectionsOpenedAndClosedAfterTwoSelects()
			throws InterruptedException {
		CountDownConnectionProvider cp = new CountDownConnectionProvider(2, 2);
		Database db = new Database(cp);
		db.select("select name from person").count().toBlockingObservable()
				.single();
		db.select("select name from person").count().toBlockingObservable()
				.single();
		assertTrue(cp.getsLatch().await(60, TimeUnit.SECONDS));
		assertTrue(cp.closesLatch().await(60, TimeUnit.SECONDS));
	}

	@Test
	public void testTwoConnectionsOpenedAndClosedAfterTwoUpdates()
			throws InterruptedException {
		CountDownConnectionProvider cp = new CountDownConnectionProvider(2, 2);
		Database db = new Database(cp);
		db.update("update person set score=? where name=?")
				.parameters(23, "FRED").count().toBlockingObservable().single();
		db.update("update person set score=? where name=?")
				.parameters(25, "JOHN").count().toBlockingObservable().single();
		assertTrue(cp.getsLatch().await(60, TimeUnit.SECONDS));
		assertTrue(cp.closesLatch().await(60, TimeUnit.SECONDS));
	}

	@Test
	public void testOneConnectionOpenedAndClosedAfterTwoSelectsWithinTransaction()
			throws InterruptedException {
		CountDownConnectionProvider cp = new CountDownConnectionProvider(1, 1);
		Database db = new Database(cp);
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> count = db.select("select name from person")
				.dependsOn(begin).count();
		Observable<Integer> count2 = db.select("select name from person")
				.dependsOn(count).count();
		int result = db.commit(count2).count().toBlockingObservable().single();
		log.info("committed " + result);
		cp.getsLatch().await();
		log.info("gets ok");
		cp.closesLatch().await();
		log.info("closes ok");
	}

	@Test
	public void testOneConnectionOpenedAndClosedAfterTwoUpdatesWithinTransaction()
			throws InterruptedException {
		CountDownConnectionProvider cp = new CountDownConnectionProvider(1, 1);
		Database db = new Database(cp);
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> count = db
				.update("update person set score=? where name=?")
				.dependsOn(begin).parameters(23, "FRED").count();
		Observable<Integer> count2 = db
				.update("update person set score=? where name=?")
				.dependsOn(count).parameters(25, "JOHN").count();
		int result = db.commit(count2).count().toBlockingObservable().single();
		log.info("committed " + result);
		cp.getsLatch().await();
		log.info("gets ok");
		cp.closesLatch().await();
		log.info("closes ok");
	}

	@Test
	public void testCloseDatabaseClosesConnectionProvider() {
		ConnectionProvider cp = createMock(ConnectionProvider.class);
		cp.close();
		EasyMock.expectLastCall().once();
		EasyMock.replay(cp);
		new Database(cp).close();
		EasyMock.verify(cp);
	}

	@Test
	public void testCloseAutoCommittingConnectionProviderClosesInternalConnectionProvider() {
		ConnectionProvider cp = createMock(ConnectionProvider.class);
		cp.close();
		EasyMock.expectLastCall().once();
		EasyMock.replay(cp);
		new ConnectionProviderAutoCommitting(cp).close();
		EasyMock.verify(cp);
	}

	@Test
	public void testCloseSingletonManualCommitConnectionProviderClosesInternalConnectionProvider() {
		ConnectionProvider cp = createMock(ConnectionProvider.class);
		cp.close();
		EasyMock.expectLastCall().once();
		EasyMock.replay(cp);
		new ConnectionProviderSingletonManualCommit(cp).close();
		EasyMock.verify(cp);
	}

	@Test
	public void testCloseConnectionProviderFromUrlClosesInternalConnectionProvider() {
		db().close();
	}

	@Test(expected = RuntimeException.class)
	public void testCannotPassObservableAsSingleParameter() {
		db().select("anything").parameter(Observable.from(123));
	}

	@Test
	public void testConnectionsReleasedByUpdateStatementBeforeOnNext()
			throws InterruptedException {
		final CountDownConnectionProvider cp = new CountDownConnectionProvider(
				1, 1);
		Database db = new Database(cp);
		Observable<Integer> result = db
				.update("update person set score = 1 where name=?")
				.parameter("FRED").count();

		checkConnectionsReleased(cp, result);
	}

	@Test
	public void testConnectionsReleasedByCommitBeforeOnNext()
			throws InterruptedException {
		final CountDownConnectionProvider cp = new CountDownConnectionProvider(
				1, 1);
		Database db = new Database(cp);
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> result = db
				.update("update person set score = 1 where name=?")
				.dependsOn(begin).parameter("FRED").count();
		checkConnectionsReleased(cp, db.commit(result));
	}

	@Test
	public void testConnectionsReleasedByRollbackBeforeOnNext()
			throws InterruptedException {
		final CountDownConnectionProvider cp = new CountDownConnectionProvider(
				1, 1);
		Database db = new Database(cp);
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> result = db
				.update("update person set score = 1 where name=?")
				.dependsOn(begin).parameter("FRED").count();
		checkConnectionsReleased(cp, db.rollback(result));
	}

	private void checkConnectionsReleased(final CountDownConnectionProvider cp,
			Observable<?> result) throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		result.subscribe(new Action1<Object>() {

			@Override
			public void call(Object obj) {
				try {
					if (cp.closesLatch().await(TIMEOUT_SECONDS,
							TimeUnit.SECONDS))
						latch.countDown();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
	}

	@Test
	public void testCanChainUpdateStatementsWithinTransaction() {
		Database db = db();
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> updates = Observable
		// set name parameter
				.from("FRED")
				// push into update
				.lift(db.update("update person set score=1 where name=?")
						.dependsOn(begin).parameterOperator())
				// map num rows affected to JOHN
				.map(constant("JOHN"))
				// push into second update
				.lift(db.update("update person set score=2 where name=?")
						.parameterOperator());
		db.commit(updates).toBlockingObservable().single();
	}

	@Test
	public void testCommitOperator() {
		Database db = db();
		Observable<Boolean> begin = db.beginTransaction();
		String name = Observable
		// set name parameter
				.from("FRED")
				// push into update
				.lift(db.update("update person set score=1 where name=?")
						.dependsOn(begin).parameterOperator())
				// map num rows affected to JOHN
				.lift(db.commitOperator())
				// select query
				.lift(db.select("select name from person where score=1")
				// depends on commit
						.dependsOnOperator()
						// return names
						.getAs(String.class))
				// return first name
				.first()
				// block to get make everything run
				.toBlockingObservable().single();
		assertEquals("FRED", name);
	}

	@Test
	public void testChainSelectUsingOperators() {
		Database db = db();
		List<Integer> scores = db.select("select name from person")
		// get name
				.getAs(String.class)
				// push name as parameter to next select
				.lift(db
				// select scores
				.select("select score from person where name=?")
				// parameters are pushed
						.parameterOperator()
						// get score as integer
						.getAs(Integer.class))
				// sort scores
				.toSortedList()
				// block to get result
				.toBlockingObservable().single();
		assertEquals(asList(21, 25, 34), scores);
	}

	@Test
	public void testBeginTransactionEmitsOneItem() {
		Database db = db();
		Boolean value = db.beginTransaction().toBlockingObservable().single();
		assertTrue(value);
	}

	@Test
	public void testCommitOnLastOperator() {
		Database db = db();
		long count = db
		// start transaction
				.beginTransaction()
				// push parameters
				.concatMap(constant(from(asList(99, 88))))
				// log
				.doOnEach(log())
				// update twice
				.lift(db.update("update person set score=?")
				// push parameters
						.parameterOperator())
				// commit on last
				.lift(db.commitOnCompleteOperator())
				// get count of 88s
				.lift(db.select("select count(*) from person where score=88")
				// depends on previous
						.dependsOnOperator()
						// count as Long
						.getAs(Long.class))
				// block and get result
				.toBlockingObservable().single();
		assertEquals(3, count);
	}

	@Test
	public void testRollbackOnLastOperator() {
		Database db = db();
		long count = db
		// start transaction
				.beginTransaction()
				// push parameters
				.concatMap(constant(from(asList(99, 88))))
				// log
				.doOnEach(log())
				// update twice
				.lift(db.update("update person set score=?")
				// push parameters
						.parameterOperator())
				// commit on last
				.lift(db.rollbackOnCompleteOperator())
				// get count of 88s
				.lift(db.select("select count(*) from person where score=88")
				// depends on previous
						.dependsOnOperator()
						// count as Long
						.getAs(Long.class))
				// block and get result
				.toBlockingObservable().single();
		assertEquals(0, count);
	}

	@Test
	public void testBeginTransactionOnNextForThreePasses() {
		Database db = db();
		Observable<Integer> min = Observable
		// do 3 times
				.from(asList(11, 12, 13))
				// begin transaction for each item
				.lift(db.beginTransactionOnNextOperator())
				// update all scores to the item
				.lift(db.update("update person set score=?")
						.parameterOperator())
				// to empty parameter list
				.map(toEmpty())
				// increase score
				.lift(db.update("update person set score=score + 5")
						.parameterListOperator())
				// only expect one result so can flatten
				.lift(RxUtil.<Integer> flatten())
				// commit transaction
				.lift(db.commitOnNextOperator())
				// to empty lists
				.map(toEmpty())
				// return count
				.lift(db.select("select min(score) from person")
						.dependsOnOperator().getAs(Integer.class));
		assertIs(18, min);
	}

	@Test
	public void testParameterListOperator() {
		Database db = db();
		@SuppressWarnings("unchecked")
		int count =
		// parameters grouped in lists
		objects(objects(1), objects(2))
		// log
				.doOnEach(log())
				// begin trans
				.lift(db.<Observable<Object>> beginTransactionOnNextOperator())
				// log
				.doOnEach(log())
				// update
				.lift(db.update("update person set score = ?")
				// push lists of parameters
						.parameterListOperator())
				// log
				.doOnEach(log())
				// commit
				.lift(db.<Integer> commitOnNextListOperator())
				// total rows affected
				.count()
				// block and get result
				.toBlockingObservable().single();
		assertEquals(2, count);
	}

	@Test
	public void testParameterListOperatorWhenQueryNeedsTwoParameters() {
		Database db = db();
		@SuppressWarnings("unchecked")
		int count =
		// parameters grouped in lists
		objects(objects(1, "FRED", 3, "JOHN"), objects(2, "JOSEPH"))
		// log
				.doOnEach(log())
				// begin trans
				.lift(db.<Observable<Object>> beginTransactionOnNextOperator())
				// log
				.doOnEach(log())
				// update
				.lift(db.update("update person set score = ? where name=?")
				// push lists of parameters
						.parameterListOperator())
				// log
				.doOnEach(log())
				// commit
				.lift(db.<Integer> commitOnNextListOperator())
				// total rows affected
				.count()
				// block and get result
				.toBlockingObservable().single();
		assertEquals(2, count);
	}

	@Test
	public void testCanExecuteCreateSchema() {
		Database db = db();
		int count = db.update("create schema if not exists special_user")
				.count().toBlockingObservable().single();
		assertEquals(0, count);
	}

	@Test
	public void testCanExecuteCreateTable() {
		Database db = db();
		int count = db
				.update("create table  mytemp(name varchar2(100) primary key)")
				.count().toBlockingObservable().single();
		assertEquals(0, count);
	}

	private static Observable<Object> objects(Object... objects) {
		return Observable.from(objects);
	}

	private static Observable<Observable<Object>> objects(
			Observable<Object>... objects) {
		return Observable.from(objects);
	}

	@Test
	public void testDatabaseFromConnectionCanUseConnectionTwiceWithoutItBeingClosedInReality()
			throws SQLException {
		ConnectionProvider cp = DatabaseCreator.connectionProvider();
		DatabaseCreator.createDatabase(cp);
		Connection con = cp.get();
		Database db = Database.from(con);
		Observable<Integer> count = db
		// get names
				.select("select name from person")
				// as string
				.getAs(String.class)
				// count names
				.count()
				// do something else
				.lift(db
				// get max score
				.select("select max(score) from person")
				// run the previous statement first
						.dependsOnOperator()
						// as integer
						.getAs(Integer.class));
		assertIs(34, count);
		con.close();
	}

	@Test
	public void testNonTransactionalMultipleQueries() {
		// get a synchronous database
		Database db = DatabaseCreator.db();
		final Set<String> set = Collections
				.newSetFromMap(new HashMap<String, Boolean>());
		Observable<Integer> count = Observable.from(asList(1, 2, 3, 4, 5))
		// select
				.lift(db.select("select name from person where score >?")
				// push parameters to this query
						.parameterOperator()
						// get name as string
						.getAs(String.class))
				// record thread name
				.doOnNext(new Action1<String>() {
					@Override
					public void call(String name) {
						set.add(Thread.currentThread().getName());
					}
				})
				// count
				.count();
		assertIs(5 * 3, count);
		System.out.println("threads=" + set);
		assertEquals(1, set.size());
	}

	@Test
	public void testTransaction() {
		Database db = db();
		Observable<Boolean> begin = db.beginTransaction();
		Observable<Integer> count = Observable
		// generate 1,2,3
				.from(asList(1, 2, 3))
				// update score with that value
				.lift(db.update("update person set score = ?")
				// participates in a transaction
						.dependsOn(begin)
						// parameters are pushed to this update statement
						.parameterOperator())
				// commit transaction
				.lift(db.commitOnCompleteOperator())
				// count names with score 3
				.lift(db.select("select count(name) from person where score=3")
				// must commit first
						.dependsOnOperator().getAs(Integer.class));
		assertIs(3, count);
	}

	private static class CountDownConnectionProvider implements
			ConnectionProvider {
		private final ConnectionProvider cp;
		private final CountDownLatch closesLatch;
		private final CountDownLatch getsLatch;

		CountDownConnectionProvider(int expectedGets, int expectedCloses) {
			this.cp = connectionProvider();
			DatabaseCreator.createDatabase(cp.get());
			this.closesLatch = new CountDownLatch(expectedCloses);
			this.getsLatch = new CountDownLatch(expectedGets);
		}

		CountDownLatch closesLatch() {
			return closesLatch;
		}

		CountDownLatch getsLatch() {
			return getsLatch;
		}

		@Override
		public Connection get() {
			getsLatch.countDown();
			Connection inner = cp.get();
			return new CountingConnection(inner, closesLatch);
		}

		@Override
		public void close() {
			cp.close();
		}
	}

	static class PersonClob {
		private final String name;
		private final String document;

		public PersonClob(String name, String document) {
			this.name = name;
			this.document = document;
		}

		public String getName() {
			return name;
		}

		public String getDocument() {
			return document;
		}
	}

	static class PersonBlob {
		private final String name;
		private final byte[] document;

		public PersonBlob(String name, byte[] document) {
			this.name = name;
			this.document = document;
		}

		public String getName() {
			return name;
		}

		public byte[] getDocument() {
			return document;
		}
	}

	static class Person {
		private final String name;
		private final double score;
		private final Long dateOfBirthEpochMs;
		private final Long registered;

		Person(String name, double score, Long dateOfBirthEpochMs,
				Long registered) {
			this.name = name;
			this.score = score;
			this.dateOfBirthEpochMs = dateOfBirthEpochMs;
			this.registered = registered;
		}

		public String getName() {
			return name;
		}

		public double getScore() {
			return score;
		}

		public Long getDateOfBirth() {
			return dateOfBirthEpochMs;
		}

		public Long getRegistered() {
			return registered;
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("Pair [name=");
			builder.append(name);
			builder.append(", score=");
			builder.append(score);
			builder.append("]");
			return builder.toString();
		}
	}

}