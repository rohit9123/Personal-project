package reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

class Person {
    private String name;
    private int age;

    public Person(String name, int age) {
        this.name = name;
        this.age = age;
    }

    private void sayHello(String message) {
        System.out.println(name + " says: " + message);
    }

    @Override
    public String toString() {
        return "Person{name='" + name + "', age=" + age + "}";
    }
}

class Singleton {
    private static Singleton instance = new Singleton();

    private Singleton() {
        // Private constructor
    }

    public static Singleton getInstance() {
        return instance;
    }
}

public class ReflectionExample {
    public static void main(String[] args) throws Exception {
        // --- 1. Accessing Private Members ---
        Class<?> personClass = Person.class;
        Person person = new Person("John", 30);
        System.out.println("Before modification: " + person);

        Field nameField = personClass.getDeclaredField("name");
        nameField.setAccessible(true); // Bypass private
        nameField.set(person, "Alice");

        System.out.println("After modification: " + person);

        Method helloMethod = personClass.getDeclaredMethod("sayHello", String.class);
        helloMethod.setAccessible(true); // Bypass private
        helloMethod.invoke(person, "Reflection accessed this private method!");

        // --- 2. Breaking Singleton ---
        System.out.println("\n--- Breaking Singleton ---");
        Singleton instance1 = Singleton.getInstance();
        
        // Using reflection to call private constructor
        java.lang.reflect.Constructor<Singleton> constructor = Singleton.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Singleton instance2 = constructor.newInstance();

        System.out.println("Instance 1 HashCode: " + instance1.hashCode());
        System.out.println("Instance 2 HashCode: " + instance2.hashCode());
        System.out.println("Are they the same? " + (instance1 == instance2));
    }
}
