Working variant (safe change):

This variant adds a harmless method to Person in both client and server.
Run steps:
  javac -d out src\tcp01\*.java
  java -cp out tcp01.TCPServer   (terminal A)
  java -cp out tcp01.TCPClient   (terminal B)
Expected: client prints "Received: Lisboa" (server replies with locality)

No serialVersionUID change -> serialization remains compatible.
