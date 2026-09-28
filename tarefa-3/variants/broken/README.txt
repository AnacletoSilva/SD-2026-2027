Broken variant (incompatible version):

This variant changes serialVersionUID of Person in the SERVER to 2L while the client keeps 1L.
Run steps:
  javac -d out src\tcp01\*.java
  java -cp out tcp01.TCPServer   (terminal A)
  java -cp out tcp01.TCPClient   (terminal B)
Expected: client sends object; server throws InvalidClassException when deserializing; exception seen on server side.
