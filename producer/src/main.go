package main

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
	"strconv"
	"strings"

	"github.com/confluentinc/confluent-kafka-go/kafka"
)

type Payload struct {
	Year      int      `json:"year"`
	Maker     string   `json:"maker"`
	Model     string   `json:"model"`
	BodyStyle []string `json:"body_style"`
}

func getDataString(year int) (string, error) {
	resp, err := http.Get(fmt.Sprintf("https://raw.githubusercontent.com/abhionlyone/us-car-models-data/master/%d.csv", year))
	if err != nil {
		return "", nil
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK {
		bodyBytes, err := ioutil.ReadAll(resp.Body)
		if err != nil {
			log.Fatal(err)
		}
		bodyString := string(bodyBytes)
		return bodyString, nil
	}
	return "", nil
}

func main() {

	p, err := kafka.NewProducer(&kafka.ConfigMap{"bootstrap.servers": "kafka-server:9092"})
	if err != nil {
		panic(err)
	}
	defer p.Close()

	// Delivery report handler for produced messages
	go func() {
		for e := range p.Events() {
			switch ev := e.(type) {
			case *kafka.Message:
				if ev.TopicPartition.Error != nil {
					fmt.Printf("Delivery failed: %v\n", ev.TopicPartition)
				} else {
					fmt.Printf("Delivered message to %v\n", ev.TopicPartition)
				}
			}
		}
	}()

	topic := "car-makers"
	for year := 1992; year <= 2021; year++ {
		in, err := getDataString(year)
		if err != nil {
			continue
		}

		r := csv.NewReader(strings.NewReader(in))
		records, err := r.ReadAll()

		if err != nil {
			log.Fatal(err)
			continue
		}
		for i := 1; i < len(records); i++ {
			record := records[i]
			y, err := strconv.Atoi(record[0])
			if err != nil {
				log.Fatal(err)
				continue
			}
			var bs []string
			err = json.Unmarshal([]byte(record[3]), &bs)

			pl := Payload{
				Year:      y,
				Maker:     record[1],
				Model:     record[2],
				BodyStyle: bs,
			}
			js, _ := json.Marshal(pl)
			fmt.Println(pl)
			p.Produce(&kafka.Message{
				TopicPartition: kafka.TopicPartition{Topic: &topic, Partition: kafka.PartitionAny},
				Value:          js,
			}, nil)
			r := p.Flush(1000)
			fmt.Println(r)
		}
	}
}
