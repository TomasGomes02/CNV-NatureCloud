#!/bin/bash

echo "Starting EXHAUSTIVE Multi-Dimensional KNN Grid Generator..."
echo "Waiting 10s for the cluster to stabilize..."
sleep 10

# ==========================================
# 1. FRACTALS (Non-Square Aspect Ratios)
# ==========================================
echo "[1/3] Sweeping Asymmetric Fractals Space..."

h_idx=0

for w in {100..10000..100}; do
	for h in {100..10000..100}; do
    	for i in {100..100..100}; do

        	echo " -> Fractal: ${w}x${h}, iters=${i}"
        	curl -s "http://localhost:8080/fractals?w=${w}&h=${h}&iterations=${i}" > /dev/null &
    done
    wait
    sleep 2 
done


# ==========================================
# 2. GRAY-SCOTT (Chemical Reaction Rates)
# ==========================================
echo "[2/3] Sweeping Complex Gray-Scott Space..."

profile_idx=0

for s in {100..2000..100}; do
    for i in {1000..10000..200}; do
        
        # Universal switch statement for Chemical Profiles
        case $profile_idx in
            0) f=0.010; k=0.045; ext=true; mode="center" ;;
            1) f=0.020; k=0.055; ext=false; mode="ring" ;;
            2) f=0.030; k=0.062; ext=true; mode="stripe" ;;
            3) f=0.040; k=0.065; ext=false; mode="center" ;;
            4) f=0.050; k=0.070; ext=true; mode="stripe" ;;
        esac
        
        echo " -> GrayScott: size=${s}, iters=${i}, f=${f}, k=${k}, ext=${ext}, mode=${mode}"
        curl -s "http://localhost:8080/grayscott?size=${s}&maxIterations=${i}&f=${f}&k=${k}&stopOnExtinction=${ext}&seedMode=${mode}" > /dev/null &
        
        profile_idx=$(( (profile_idx + 1) % 5 ))
    done
    wait
    sleep 3
done


# ==========================================
# 3. DNA (Variable Lengths)
# ==========================================
echo "[3/3] Sweeping Dense DNA Space..."

HUMAN_BASE="ATGGTGCATCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTT"
CHIMP_BASE="ATGGTGCACCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTT"

# Generate 2800-character strings
HUMAN_GIANT=""
CHIMP_GIANT=""
for build in {1..20}; do
    HUMAN_GIANT="${HUMAN_GIANT}${HUMAN_BASE}"
    CHIMP_GIANT="${CHIMP_GIANT}${CHIMP_BASE}"
done

off_idx=0

for len in {100..2000..100}; do
    for min in {1..20..2}; do
        
        # Universal switch statement for DNA offsets
        case $off_idx in
            0) offset=0 ;;
            1) offset=-50 ;;
            2) offset=50 ;;
            3) offset=-100 ;;
            4) offset=100 ;;
        esac

        chimp_len=$(( len + offset ))
        if [ "$chimp_len" -lt 50 ]; then chimp_len=50; fi

        human_seq="${HUMAN_GIANT:0:$len}"
        chimp_seq="${CHIMP_GIANT:0:$chimp_len}"
        
        echo " -> DNA: seq1Len=${len}, seq2Len=${chimp_len}, minLength=${min}"
        curl -s "http://localhost:8080/dna?seq1=human%3A%3E${human_seq}&seq2=chimp%3A%3E${chimp_seq}&minLength=${min}&stopOnFirst=false" > /dev/null &
        
        off_idx=$(( (off_idx + 1) % 5 ))
    done
    wait
    sleep 2
done

echo "---------------------------------------------------------"
echo "Exhaustive Grid Search Complete."
