import dev.w1zzrd.asm.Directives;
import dev.w1zzrd.asm.InjectClass;
import dev.w1zzrd.asm.Inject;

import java.util.concurrent.ThreadLocalRandom;

import static dev.w1zzrd.asm.InPlaceInjection.*;

@InjectClass(value = MergeTest.class)
public class MergeInject extends MergeTest implements Runnable {

    @Inject
    public int number;

    // Dummy field
    String s;

    /*
    @Inject
    MergeInject() {
        s = "Hello";
        number = 10;
    }

     */


    @Inject(value = BEFORE, target = "stackTest()I")
    public int beforeStackTest() {
        System.out.println("This is before stack test");
        if (ThreadLocalRandom.current().nextBoolean()) {
            System.out.println("Shortcut");
            return 69420;
        }

        Directives.callOriginal();
        return 0;
    }


    @Inject(value = AFTER, target = "stackTest()I", acceptOriginalReturn = true)
    public int stackTest(int arg) {
        Runnable r = () -> {
          System.out.println(arg / 15);
          System.out.println("Heyo");
        };
        r.run();
        return 69;
    }


    @Inject(value = AFTER, target = "test()Ljava/lang/String;", acceptOriginalReturn = true)
    public String test(String retVal){

        System.out.println(retVal + "Cringe");

        return "Modified";
    }

    /*
    @Inject(value = AFTER, target = "test()Ljava/lang/String;", acceptOriginalReturn = true)
    public String test_inject$1(String retVal) {
        System.out.println("Another injection: "+retVal);
        return retVal;
    }
     */











    @Override
    @Inject
    public void run() {
        for (int i = 0; i < 5; ++i) {
            s = s + "!";
            System.out.println(test()+'\n');
        }
    }

    public String test1(){ return null; }
}
