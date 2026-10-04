package android.os;
import java.util.*;
public class Handler {
 public static long now; private static long order;
 private static final class Task { final long time,seq; final Runnable action; Task(long t,Runnable r){time=t;seq=order++;action=r;} }
 private static final PriorityQueue<Task> tasks=new PriorityQueue<>(Comparator.comparingLong((Task t)->t.time).thenComparingLong(t->t.seq));
 public Handler(Looper l){} public boolean postDelayed(Runnable r,long ms){tasks.add(new Task(now+ms,r));return true;}
 public void removeCallbacks(Runnable r){tasks.removeIf(t->t.action==r);}
 public static void advance(long ms){long end=now+ms;int limit=20000;while(!tasks.isEmpty()&&tasks.peek().time<=end){if(--limit==0)throw new AssertionError("scheduler spun");Task t=tasks.remove();now=t.time;t.action.run();}now=end;}
 public static void reset(){now=0;order=0;tasks.clear();}
}
