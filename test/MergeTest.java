import dev.w1zzrd.asm.Merger;

public class MergeTest {

    String s;


    public MergeTest(){
        s = "Hello";
    }

    public MergeTest(String s) {
        this.s = s;
    }

    public String test(){
        Class<?> c = Merger.class;
        Runnable r = () -> {
            System.out.println(c.getName());
        };

        System.out.println(r);
        r.run();
        return s + "Test";
    }

    public String test1(){
        return s;
    }

    public void stackTest() {
        String str = Integer.toString(getNumber() * 23);

        if ("69".equals(str)) {
            int k = Integer.getInteger(str);

            System.out.println(k + str + (k * k));
            getNumber();
        }

        float f = getNumber() * 2.5f;

        System.out.println(f + str + (f * f));

        multiArg(str, f, f == 5f, "69".equals(str) ? f * f : (f + 1.0), f < 6f ? (int)f : 7);
    }


    private static int getNumber() {
        return 3;
    }

    private static void multiArg(String k, float a, boolean bool, double b, int i) {
        if (bool)
            System.out.println(k + a + b * getNumber() + i);
    }
}
