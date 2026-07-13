# Take-Home Assignment: Real-Time Data Anomaly Detector

This assignment evaluates your ability to design and implement a small system that processes data from a message queue, applies a simple statistical model, and is containerized for easy deployment.

## The Scenario
Your team is responsible for a core service that processes a continuous stream of event data.

As a Software Engineer, you need to quickly prototype a real-time anomaly detection service (the Consumer) that monitors a simple metric in this stream, which is being published by a separate Producer service.

## The Goal
Design and implement two coordinated components—a Producer and a Consumer—that communicate via a message queue system, with the Consumer identifying sudden, significant data deviations (anomalies).

## Requirements

### 1. Data Flow & Input
The application must coordinate three separate components using Docker:

| Component | Role | Requirement |
| :--- | :--- | :--- |
| **Messaging System** | The Queue/Stream | Use a lightweight, containerized queue system (e.g., RabbitMQ, Redis Pub/Sub, or SNS/SQS using Localstack). This will run as a separate service or check Appendix 1: Alternative Data Flow Options. |
| **Producer Service** | Data Generator | A simple script/application that continuously generates and publishes numerical data points (e.g., floating-point numbers) to the Messaging System or alternative data flow. The data should mostly follow a normal distribution but must occasionally introduce an intentional, significant outlier (the anomaly). |
| **Consumer Service** | Anomaly Detector | The main application that subscribes to the queue, processes each data point as it arrives, and runs the anomaly detection logic. |

### 2. Statistical Model / Core Logic
Implement a simple statistical anomaly detection logic within the Consumer Service:
* Maintain a rolling window of the N most recent data points (e.g., N = 50 to 100).
* For each new data point (x_t), calculate the mean (μ) and standard deviation (σ) of the current rolling window.
* Determine if the new data point is an anomaly using a Z-score test:
  `Z = |x_t - μ| / σ`
* The data point x_t is considered an anomaly if its Z-score exceeds a predefined threshold (e.g., Z > 3).

### 3. Output / Alerting
The Consumer Service must log its status to the console for each processed data point.
* **Normal:** `[TIMESTAMP] Data point: X.XX | Status: OK | Z-score: Z.ZZ`
* **Anomaly:** `[TIMESTAMP] Data point: X.XX | Status: ANOMALY DETECTED! | Z-score: Z.ZZ | ALERT: Significant deviation detected.`

### 4. Deliverables
* **Source Code:** A complete, well-structured project written in a programming language of your choice.
* **Dockerization & Orchestration:**
  1. A Dockerfile for the Producer and one for the Consumer.
  2. A comprehensive `docker-compose.yml` file that orchestrates all three services: the Messaging System, the Producer, and the Consumer.
* **Documentation:** A brief `README.md` file that includes:
  1. Include all relevant information you judge important for a project of this nature.
  2. "Up Next" section:
     * What additional tooling would you use or recommend to create and maintain the code for real if it isn't already included (testing, API tools, linting, build files etc.)?
     * Would it eventually be deployable to an orchestration platform such as Kubernetes or similar - what else would need to be provided to make this work?
     * What are the most obvious missing technical requirements for the service and do you have some thoughts on those?

## Appendix 1: Alternative Data Flow Options (For simpler implementations)

| Option | Requirement |
| :--- | :--- |
| **Option A (Internal Simulation)** | Implement a single-container application that internally generates the data points periodically (no external queue/producer needed). |
| **Option B (File/Mock API)** | Implement a single-container application that reads from a simple file or mocks a REST API endpoint to fetch new data points. |
