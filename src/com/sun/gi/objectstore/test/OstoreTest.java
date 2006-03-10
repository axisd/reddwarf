package com.sun.gi.objectstore.test;

import com.sun.gi.objectstore.NonExistantObjectIDException;
import com.sun.gi.objectstore.ObjectStore;
import com.sun.gi.objectstore.Transaction;
import java.io.Serializable;
import com.sun.gi.objectstore.DeadlockException;
import com.sun.gi.objectstore.tso.TSOObjectStore;
import com.sun.gi.objectstore.tso.dataspace.InMemoryDataSpace;
import com.sun.gi.objectstore.tso.dataspace.PersistantInMemoryDataSpace;

/**
 * <p>
 * Title:
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2003
 * </p>
 * <p>
 * Company:
 * </p>
 * 
 * @author not attributable
 * @version 1.0
 */

class DataObject implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -5105752483489836216L;

	int i;

	double d;

	String s;

	public DataObject(int i, double d, String s) {
		this.d = d;
		this.s = s;
		this.i = i;
	}

	public String toString() {
		return "Iint = " + i + ", double = " + d + ", String= " + s;
	}
}

public class OstoreTest {
	private static boolean TESTISOLATION = true;

	public OstoreTest() {
	}

	public static void main(String[] args) {
		ObjectStore ostore = null;
		try {
			//ostore = new TSOObjectStore(new PersistantInMemoryDataSpace(1));
			 ostore = new TSOObjectStore(new InMemoryDataSpace(1));
		} catch (InstantiationException e3) {
			e3.printStackTrace();
			System.exit(1);
		}
		System.out.println("Clearing object store");
		ostore.clear();
		System.out.println("Assigning transactions.");
		final Transaction t1 = ostore.newTransaction(null);
		t1.start();
		Transaction t2 = ostore.newTransaction(null);
		t2.start();
		System.out.println("Creating test object 1.");
		DataObject obj = new DataObject(55, 3.14, "This is a test!");
		final long objid = t1.create(obj, "Test_Data");
		System.out.println("Creating test object 2.");
		obj = new DataObject(100, 1.5, "This is test 2!");
		final long objid2 = t1.create(obj, "Test_Data2");
		obj = new DataObject(500, 22.52, "This is test 3!");
		final long objid3 = t1.create(obj, "Test_Data3");
		System.out.println("Getting object from inside transaction...");
		try {
			obj = (DataObject) t1.lock(objid);
		} catch (DeadlockException e2) {
			e2.printStackTrace();
		} catch (NonExistantObjectIDException e2) {
			e2.printStackTrace();
		}
		if (obj == null) {
			System.out
					.println("ERROR: failed to lock object from creation context.");
		} else {
			System.out.println("Success: Object: " + obj.toString());
		}

		System.out.println("Testing lock of a non existant object.");
		obj = null;
		try {
			obj = (DataObject) t2.lock(Long.MAX_VALUE);
			System.out.println("ERROR: returned object.  Object: "
					+ obj.toString());
			System.exit(2);
		} catch (DeadlockException e2) {
			e2.printStackTrace();
		} catch (NonExistantObjectIDException e2) {
			System.out.println("Success: failed to lock non-existant object.");
		}

		System.out.println("COmitting object");
		t1.commit();
		System.out
				.println("Attempting to retrieve object from other transaction.");
		try {
			obj = (DataObject) t2.lock(objid);
		} catch (DeadlockException e1) {
			e1.printStackTrace();
		} catch (NonExistantObjectIDException e1) {
			e1.printStackTrace();
		}
		if (obj != null) {
			System.out.println("Success: Object " + obj.toString());
		} else {
			System.out.println("ERROR: failed to find object");
		}
		System.out.println("Testing update");
		obj.d = 9999.0;
		obj.i = 9999;
		obj.s = "ninenineninenine";
		t2.commit();
		try {
			obj = (DataObject) t1.lock(objid);
		} catch (DeadlockException e1) {
			e1.printStackTrace();
		} catch (NonExistantObjectIDException e1) {
			e1.printStackTrace();
		}
		if ((obj.d == 9999.0) && (obj.i == 9999)
				&& (obj.s.equals("ninenineninenine"))) {
			System.out.println("Data sucessfully stored and retrieved.");
		} else {
			System.err.println("ERROR: Data update not properly retrieved!");
		}
		t1.commit();
		t2.commit();
		System.out.println("Looking up ID by name.");
		long lid = t2.lookup("Test_Data");
		if (lid == objid) {
			System.out.println("Success: found id");
		} else {
			System.out.println("ERROR: id = " + objid + " but found " + lid);
		}
		System.out.println("Looking up INVALID name.");
		lid = t2.lookup("NOTANOBJECT");
		if (lid == ObjectStore.INVALID_ID) {
			System.out.println("Success: found INVALID_ID");
		} else {
			System.out.println("ERROR: wanted INVALID_ID = "
					+ ObjectStore.INVALID_ID + " but found " + lid);
		}
		t2.commit();
		System.out.println("*****  TSO tests ***");
		final Transaction firstTrans = ostore.newTransaction(null);
		firstTrans.start();
		try {
			Thread.sleep(1000);
		} catch (Exception e) {
			e.printStackTrace();
		}
		final Transaction secondTrans = ostore.newTransaction(null);
		secondTrans.start();
		System.out.println("First Trans = " + firstTrans);
		System.out.println("Second Trans = " + secondTrans);
		System.out
				.println("TSO Interrupt test 1: earlier inetrrupts later wait");
		Thread lockThread = new Thread() {
			public void run() {
				try {
					System.out
							.println("In later trans thread, acquiring first lock.");
					secondTrans.lock(objid);
					System.out.println("acquired first lock.");
					Thread.sleep(3000);
					System.out.println("Acquiring second lock in later..  ");
					secondTrans.lock(objid2);
					System.out.println("ERROR: Later timstamp got both locks!");
				} catch (DeadlockException dle) {
					System.out
							.println("Later Transaction successfully interrupted");
					secondTrans.abort();
				} catch (Exception e) {
					System.out
							.println("ERROR: Unexpected exception in Later Transaction .");
					e.printStackTrace();
				}
			}
		};
		lockThread.start();
		try {
			Thread.sleep(1000);
			System.out.println("Acquiring second lock in earlier transaction.");
			firstTrans.lock(objid2);
			System.out.println("Earlier transaction acquired second.");
			Thread.sleep(5000);
			System.out.println("Acquiring first lock in earlier transaction.");
			firstTrans.lock(objid);
		} catch (DeadlockException dle) {
			System.out.println("ERROR: Earlier transaction Aborted.");
		} catch (Exception e) {
			System.out
					.println("ERROR: Unexpected Exception in earlier transaction.");
		}
		firstTrans.commit();
		System.out.println("Earlier trasnaction completed.");
		testCreate(ostore);
		System.out.println("Closing ostore");
		ostore.close();
		System.out.println("End of tests");
	}

	private static void testCreate(ObjectStore ostore) {
		System.out.println("Named create blocking tests");
		final Transaction t1 = ostore.newTransaction(null);
		t1.start();		
		final Transaction t2 = ostore.newTransaction(null);
		t2.start();
		
		DataObject obj = new DataObject(55, 3.14, "This is a test!");
		
		// commit test
		long time = System.currentTimeMillis();
		final long objid = t1.create(obj, "CreationTests");
		if (objid == ObjectStore.INVALID_ID){
			System.out.println("Error: invalid id from initial create");
			System.exit(1);
		}
		try {
			(new Thread() {
				public void run() {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {

						e.printStackTrace();
					}
					t1.commit();
					System.out.println(".... t1 commited");
				}
			}).start();
			obj = (DataObject) t2.lock(objid);
			if (System.currentTimeMillis() < time + 5000) {
				System.out.println("ERROR: did not block!");
				System.exit(1);
			} else {
				if (obj == null) {
					System.out.println("Error: blocked but failed to return obj!");
					System.exit(1);
				}
				System.out.println("Success: blocked til commit!");
				t2.abort();
			}
		} catch (DeadlockException e) {
			e.printStackTrace();
		} catch (NonExistantObjectIDException e) {
			System.out.println("Failure: claims obj does not exist.");
		}
		
		// abort test
		t1.start();
		t2.start();

		
		final long objid2 = t1.create(obj, "CreationTests2");
		if (objid2 == ObjectStore.INVALID_ID){
			System.out.println("Error: invalid id from initial create");
			System.exit(1);
		}
		time = System.currentTimeMillis();
		try {
			(new Thread() {
				public void run() {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {

						e.printStackTrace();
					}
					t1.abort();
					System.out.println(".... t1 aborted");
				}
			}).start();
			obj = (DataObject) t2.lock(objid2);
			if (System.currentTimeMillis() < time + 5000) {
				System.out.println("ERROR: did not block!");
				System.exit(1);
			} else {
				if (obj != null) {
					System.out.println("Error: blocked but failed to return null!");
					System.exit(1);
				}
				System.out.println("Success: blocked til abort!");
				t2.abort();
			}
		} catch (DeadlockException e) {
			e.printStackTrace();
		} catch (NonExistantObjectIDException e) {
			System.out.println("Success: claims obj does not exist.");
		}
		
		// create v. create and commit test
		t1.start();
		t2.start();

		
		final long objid3 = t1.create(obj, "CreationTests3");
		if (objid3 == ObjectStore.INVALID_ID){
			System.out.println("Error: invalid id from initial create");
			System.exit(1);
		}
		time = System.currentTimeMillis();
		try {
			(new Thread() {
				public void run() {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {

						e.printStackTrace();
					}
					t1.commit();
					System.out.println(".... t1 aborted");
				}
			}).start();
			long objid4 = t2.create(obj, "CreationTests3");
			if (System.currentTimeMillis() < time + 5000) {
				System.out.println("ERROR: did not block!");
				System.exit(1);
			} else {
				if (objid4 != ObjectStore.INVALID_ID) {
					System.out.println("Error: blocked but failed returned "+objid4);
					System.exit(1);
				}
				System.out.println("Success: blocked til commit and returned INVALID_ID");
				t2.abort();
			}
		} catch (DeadlockException e) {
			e.printStackTrace();
		}
		
//		 create v. create and abort test
		t1.start();
		t2.start();

		
		final long objid4 = t1.create(obj, "CreationTests4");
		if (objid4 == ObjectStore.INVALID_ID){
			System.out.println("Error: invalid id from initial create");
			System.exit(1);
		}
		time = System.currentTimeMillis();
		try {
			(new Thread() {
				public void run() {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException e) {

						e.printStackTrace();
					}
					t1.abort();
					System.out.println(".... t1 aborted");
				}
			}).start();
			long objid5 = t2.create(obj, "CreationTests4");
			if (System.currentTimeMillis() < time + 5000) {
				System.out.println("ERROR: did not block!");
				System.exit(1);
			} else {
				if (objid5 == ObjectStore.INVALID_ID) {
					System.out.println("Error: blocked but failed returned INVALID_ID");
					System.exit(1);
				}
				System.out.println("Success: blocked til abort and returned"+objid5);
				t2.abort();
			}
		} catch (DeadlockException e) {
			e.printStackTrace();
		}
		
	}

}
