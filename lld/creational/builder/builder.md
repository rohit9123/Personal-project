# Builder Design Pattern (LLD)

## Quick Summary (TL;DR)
- **Goal**: Separate the construction of a complex object from its representation, allowing the same construction process to create different representations.
- **Key Problem Solved**: **Telescoping Constructors** (too many constructors for different parameter combinations) and **Mutable Objects** (using setters that break immutability).
- **Core Principle**: **Step-by-Step Construction** + **Immutability** (objects are read-only once created).
- **Signs you need it**:
  - A class constructor has more than 4-5 parameters, especially if many are optional or have the same data type.
  - You want to construct an object in a step-by-step, fluent manner (e.g., `User.builder().name("Alice").age(25).build()`).

---

## 1. What is the Builder Pattern?
The Builder pattern is a **Creational Design Pattern** that lets you construct complex objects step-by-step. It prevents creating constructors with huge parameter lists (telescoping constructors) and ensures the final object is fully constructed before use, preserving immutability.

---

## 2. Why to Use It (The Constructor Nightmare)

### The Problem: User Profile Class
Imagine we are creating a `User` profile object with the following fields:
* **Required**: `firstName`, `lastName`
* **Optional**: `age`, `phone`, `address`, `email`

If we use standard constructor overloading (Telescoping Constructors):
```java
public class User {
    // Required
    private String firstName;
    private String lastName;
    // Optional
    private int age;
    private String phone;
    private String address;

    // Too many constructors!
    public User(String firstName, String lastName) { ... }
    public User(String firstName, String lastName, int age) { ... }
    public User(String firstName, String lastName, int age, String phone) { ... }
    public User(String firstName, String lastName, int age, String phone, String address) { ... }
}
```
#### Why this sucks:
1. **Hard to read**: `new User("John", "Doe", 30, null, "Main St")` — what does `30` or `null` represent? It's easy to mix up arguments of the same type.
2. **Brittle**: If you add a new field, you must write even more constructors.

### Why Setters (JavaBeans pattern) are dangerous:
```java
User user = new User();
user.setFirstName("John");
user.setLastName("Doe");
// If another thread accesses 'user' here, it is in a half-initialized state!
user.setAge(30);
```
* **Loss of Immutability**: Anyone can call `setAge()` at any time later and change the object. For SDE-2 architectures, immutable objects are preferred because they are **thread-safe by default**.

---

## 3. How It Works (The Builder Solution)

We create a nested static class called `Builder` inside the main class. The client sets parameters on the builder step-by-step and finally calls `build()` to get the immutable object.

```mermaid
classDiagram
    class User {
        -String firstName
        -String lastName
        -int age
        -String phone
        -User(UserBuilder builder)
        +getFirstName() String
        +getLastName() String
        +getAge() int
        +getPhone() int
    }

    class UserBuilder {
        -String firstName
        -String lastName
        -int age
        -String phone
        +UserBuilder(String firstName, String lastName)
        +age(int age) UserBuilder
        +phone(String phone) UserBuilder
        +build() User
    }

    UserBuilder ..> User : creates (instantiates)
    UserBuilder --+ User : Nested Static Class
```

---

## 4. Code Example (Java)

Implemented in [BuilderPatternDemo.java](file:///Users/rohit.kumar.4/Documents/interview-prep/lld/creational/builder/BuilderPatternDemo.java).

### The Product and its Inner Builder
```java
public class User {
    private final String firstName; // final = Immutable
    private final String lastName;  // final = Immutable
    private final int age;
    private final String phone;

    // Private constructor: client must use the Builder
    private User(UserBuilder builder) {
        this.firstName = builder.firstName;
        this.lastName = builder.lastName;
        this.age = builder.age;
        this.phone = builder.phone;
    }

    // Getters only (No setters!)
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public int getAge() { return age; }
    public String getPhone() { return phone; }

    // Static nested Builder Class
    public static class UserBuilder {
        private final String firstName; // Required
        private final String lastName;  // Required
        private int age;                // Optional
        private String phone;           // Optional

        public UserBuilder(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }

        public UserBuilder age(int age) {
            this.age = age;
            return this; // Returns builder for chaining
        }

        public UserBuilder phone(String phone) {
            this.phone = phone;
            return this; // Returns builder for chaining
        }

        public User build() {
            User user = new User(this);
            validateUserObject(user); // Step-by-step validation can happen here!
            return user;
        }

        private void validateUserObject(User user) {
            if (user.getAge() < 0) {
                throw new IllegalArgumentException("Age cannot be negative.");
            }
        }
    }
}
```

---

## 5. Interview Angles (How to handle SDE-2 discussions)

### Question 1: "What is the role of the 'Director' in GoF Builder Pattern?"
- **Answer**: In the classic Gang of Four (GoF) book, they define a `Director` class which coordinates the step-by-step assembly of complex products (e.g., a `MealDirector` assembling `Burger`, `Drink`, and `Fries` to create a `HappyMeal`). 
- **Modern Java usage**: In modern web development, we rarely use a formal `Director` class. We directly chain methods via a fluent API (like Lombok's `@Builder`), leaving the construction sequence up to the client code.

### Question 2: "How does Lombok's `@Builder` work under the hood?"
- **Answer**: Lombok automatically generates the exact same nested static builder structure shown above during compilation. This reduces boilerplate code significantly.

### Question 3: "Is Builder Pattern Thread-Safe?"
- **Answer**: 
  - The final built object (`User`) is fully **thread-safe** because all fields are `final` and there are no setter methods (immutability).
  - However, the `Builder` object itself is **not thread-safe**. Different threads should not share the same builder instance to assemble their objects simultaneously.
