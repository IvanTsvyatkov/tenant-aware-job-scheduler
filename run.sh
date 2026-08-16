#!/bin/bash

# Tenant-Aware Job Scheduler - Single Command Startup Script
# This script starts PostgreSQL, Backend, and Frontend in one command

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Tenant Job Scheduler - Startup Script${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check prerequisites
echo -e "${YELLOW}Checking prerequisites...${NC}"

# Check Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}✗ Docker not found. Please install Docker.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker found${NC}"

# Check Docker Compose
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo -e "${RED}✗ Docker Compose not found. Please install Docker Compose.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker Compose found${NC}"

# Check Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}✗ Java not found. Please install Java 17+.${NC}"
    exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo -e "${RED}✗ Java 17+ required. Found: Java $JAVA_VERSION${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Java $JAVA_VERSION found${NC}"

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}✗ Maven not found. Please install Maven 3.8+.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Maven found${NC}"

# Check Node.js
if ! command -v node &> /dev/null; then
    echo -e "${RED}✗ Node.js not found. Please install Node.js 18+.${NC}"
    exit 1
fi
NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo -e "${RED}✗ Node.js 18+ required. Found: v$NODE_VERSION${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Node.js v$NODE_VERSION found${NC}"

echo ""

# Trap to cleanup on exit
trap cleanup EXIT INT TERM

cleanup() {
    echo ""
    echo -e "${YELLOW}Shutting down...${NC}"

    # Kill background processes
    if [ ! -z "$BACKEND_PID" ]; then
        echo "Stopping backend..."
        kill $BACKEND_PID 2>/dev/null || true
    fi

    if [ ! -z "$FRONTEND_PID" ]; then
        echo "Stopping frontend..."
        kill $FRONTEND_PID 2>/dev/null || true
    fi

    # Stop Docker Compose
    echo "Stopping PostgreSQL..."
    docker-compose down 2>/dev/null || true

    echo -e "${GREEN}Cleanup complete${NC}"
    exit 0
}

# Start PostgreSQL
echo -e "${BLUE}Starting PostgreSQL...${NC}"
docker-compose up -d

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if docker-compose exec -T postgres pg_isready -U job_scheduler_app -d job_scheduler &>/dev/null; then
        echo -e "${GREEN}✓ PostgreSQL is ready${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}✗ PostgreSQL failed to start within 30 seconds${NC}"
        exit 1
    fi
    sleep 1
done

echo ""

# Start Backend
echo -e "${BLUE}Starting Backend (Spring Boot)...${NC}"
cd backend

# Load environment variables if .env exists
if [ -f ../.env ]; then
    set -a
    source ../.env
    set +a
    echo "Loaded environment variables from .env"
fi

# Start backend in background and capture PID
# - fork=false keeps the app in Maven's JVM
# - nohup + `< /dev/null` fully detaches the process from this terminal so it
#   has no controlling tty to write to; ALL output goes only to backend.log.
nohup mvn spring-boot:run > ../backend.log 2>&1 &
BACKEND_PID=$!

# Wait for backend to be ready
echo "Waiting for backend to start..."
for i in {1..60}; do
    if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1 || \
       curl -s http://localhost:8080/jobs > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Backend is ready at http://localhost:8080${NC}"
        break
    fi
    if [ $i -eq 60 ]; then
        echo -e "${RED}✗ Backend failed to start within 60 seconds${NC}"
        echo "Check backend.log for errors"
        exit 1
    fi
    sleep 1
done

cd ..
echo ""

# Start Frontend
echo -e "${BLUE}Starting Frontend (React + Vite)...${NC}"
cd frontend

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "Installing frontend dependencies..."
    npm install
fi

# Start frontend in background
nohup npm run dev > ../frontend.log 2>&1 < /dev/null &
FRONTEND_PID=$!

# Wait for frontend to be ready
echo "Waiting for frontend to start..."
for i in {1..30}; do
    if curl -s http://localhost:5173 > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Frontend is ready at http://localhost:5173${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}✗ Frontend failed to start within 30 seconds${NC}"
        echo "Check frontend.log for errors"
        exit 1
    fi
    sleep 1
done

cd ..
echo ""

# Success!
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ All services started successfully!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}Access the application:${NC}"
echo -e "  Frontend:  ${GREEN}http://localhost:5173${NC}"
echo -e "  Backend:   ${GREEN}http://localhost:8080${NC}"
echo -e "  Database:  ${GREEN}localhost:5432${NC}"
echo ""
echo -e "${BLUE}Logs:${NC}"
echo -e "  Backend:   tail -f backend.log"
echo -e "  Frontend:  tail -f frontend.log"
echo -e "  Database:  docker-compose logs -f postgres"
echo ""
echo -e "${YELLOW}Press Ctrl+C to stop all services${NC}"
echo ""

# Keep the script running until Ctrl+C.
# Logs are written to backend.log and frontend.log; tail them manually if desired:
#   tail -f backend.log
#   tail -f frontend.log
wait $BACKEND_PID $FRONTEND_PID
