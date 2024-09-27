Here’s the revised **README.md** with the changes:

---

# Reactive System Programming

This repository contains exercises from the [**Programming Reactive Systems**](https://www.coursera.org/learn/scala-akka-reactive) course on Coursera. The project demonstrates how reactive programming principles are applied to create efficient, scalable, and responsive systems using **Scala**.

## Table of Contents
- [Overview](#overview)
- [Project Structure](#project-structure)
- [Technologies Used](#technologies-used)
- [Installation](#installation)

## Overview
Reactive system programming emphasizes handling asynchronous data streams, state changes, and events efficiently. This project focuses on:
- Solving exercises from the Coursera course on Scala and Akka reactive programming.
- Implementing reactive system examples using Scala and Akka.
- Demonstrating key reactive principles, such as backpressure, fault tolerance, and event-driven architectures.

## Project Structure
```
exercises/
│
├── actorbintree/              # Assignment week 2.
├── async/                     # Assignment week 1.
├── followers/                 # Assignment week 7.
├── kvstore/                   # Assignment week 3.
├── protocols/                 # Assignment week 5.
├── reactive-example/          # Assignment demo.
├── streaming/                 # Assignment week 6.
└── README.md                  # Project overview.
```

## Technologies Used
The project leverages the following tools and technologies:
- **Scala**: A functional and object-oriented programming language, ideal for reactive programming.
- **Akka**: Toolkit for building concurrent, distributed, and fault-tolerant applications.
- **ScalaTest**: Testing framework for Scala applications.
- **SBT**: Simple Build Tool for Scala-based projects.

## Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/AlessandroPirolo/reactive-system-programming.git
   cd reactive-system-programming
   ```

2. Build the project using SBT:
   ```bash
   sbt compile
   ```

3. Run tests to ensure everything is set up:
   ```bash
   sbt test
   ```
