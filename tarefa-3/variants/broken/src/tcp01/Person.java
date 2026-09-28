package tcp01;

import java.io.Serializable;

// Variante quebrada: serialVersionUID diferente em relação ao cliente
public class Person implements Serializable {
    private static final long serialVersionUID = 2L; // alterado -> causa InvalidClassException
n    private String name;
    private int year;
    private Place place;
n    public Person(String name, Place place, int year) {
        this.name = name;
        this.place = place;
        this.year = year;
    }
n    public String getName() { return name; }
    public int getYear() { return year; }
    public Place getPlace() { return place; }

    @Override
    public String toString() {
        return "Person{" + "name='" + name + '\'' + ", year=" + year + ", place=" + place + '}';
    }
}
