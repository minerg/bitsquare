/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.gui.main.market.trades;

import io.bitsquare.common.UserThread;
import io.bitsquare.common.util.MathUtils;
import io.bitsquare.gui.common.view.ActivatableViewAndModel;
import io.bitsquare.gui.common.view.FxmlView;
import io.bitsquare.gui.main.market.trades.charts.price.CandleStickChart;
import io.bitsquare.gui.main.market.trades.charts.volume.VolumeChart;
import io.bitsquare.gui.util.BSFormatter;
import io.bitsquare.gui.util.CurrencyListItem;
import io.bitsquare.gui.util.GUIUtil;
import io.bitsquare.locale.BSResources;
import io.bitsquare.locale.CurrencyUtil;
import io.bitsquare.trade.statistics.TradeStatistics;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.StringConverter;
import javax.inject.Inject;
import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.fxmisc.easybind.monadic.MonadicBinding;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FxmlView
public class TradesChartsView extends ActivatableViewAndModel<VBox, TradesChartsViewModel> {
    private static final Logger log = LoggerFactory.getLogger(TradesChartsView.class);

    private final BSFormatter formatter;

    private TableView<TradeStatistics> tableView;
    private ComboBox<CurrencyListItem> currencyComboBox;
    private VolumeChart volumeChart;
    private CandleStickChart priceChart;
    private NumberAxis priceAxisX, priceAxisY, volumeAxisY, volumeAxisX;
    private XYChart.Series<Number, Number> priceSeries;
    private XYChart.Series<Number, Number> volumeSeries;
    private ChangeListener<Number> priceAxisYWidthListener;
    private ChangeListener<Number> volumeAxisYWidthListener;
    private double priceAxisYWidth;
    private double volumeAxisYWidth;
    private final StringProperty priceColumnLabel = new SimpleStringProperty();
    private ChangeListener<Toggle> timeUnitChangeListener;
    private ToggleGroup toggleGroup;
    private final ListChangeListener<XYChart.Data<Number, Number>> itemsChangeListener;
    private SortedList<TradeStatistics> sortedList;
    private Label nrOfTradeStatisticsLabel;
    private ListChangeListener<TradeStatistics> tradeStatisticsByCurrencyListener;
    private ChangeListener<Number> selectedTabIndexListener;
    private SingleSelectionModel<Tab> tabPaneSelectionModel;
    private TableColumn<TradeStatistics, TradeStatistics> priceColumn, volumeColumn, marketColumn;
    private MonadicBinding<Void> currencySelectionBinding;
    private Subscription currencySelectionSubscriber;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradesChartsView(TradesChartsViewModel model, BSFormatter formatter) {
        super(model);
        this.formatter = formatter;

        // Need to render on next frame as otherwise there are issues in the chart rendering
        itemsChangeListener = c -> UserThread.execute(this::updateChartData);
    }

    @Override
    public void initialize() {
        HBox toolBox = getToolBox();
        createCharts();
        createTable();

        nrOfTradeStatisticsLabel = new Label("");
        nrOfTradeStatisticsLabel.setId("num-offers");
        nrOfTradeStatisticsLabel.setPadding(new Insets(-5, 0, -10, 5));
        root.getChildren().addAll(toolBox, priceChart, volumeChart, tableView, nrOfTradeStatisticsLabel);

        timeUnitChangeListener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                model.setTickUnit((TradesChartsViewModel.TickUnit) newValue.getUserData());
                priceAxisX.setTickLabelFormatter(getTimeAxisStringConverter());
                volumeAxisX.setTickLabelFormatter(getTimeAxisStringConverter());
            }
        };
        priceAxisYWidthListener = (observable, oldValue, newValue) -> {
            priceAxisYWidth = (double) newValue;
            layoutChart();
        };
        volumeAxisYWidthListener = (observable, oldValue, newValue) -> {
            volumeAxisYWidth = (double) newValue;
            layoutChart();
        };
        tradeStatisticsByCurrencyListener = c -> nrOfTradeStatisticsLabel.setText("Trades: " + model.tradeStatisticsByCurrency.size());
    }

    @Override
    protected void activate() {
        // root.getParent() is null at initialize
        tabPaneSelectionModel = GUIUtil.getParentOfType(root, TabPane.class).getSelectionModel();
        selectedTabIndexListener = (observable, oldValue, newValue) -> model.setSelectedTabIndex((int) newValue);
        model.setSelectedTabIndex(tabPaneSelectionModel.getSelectedIndex());
        tabPaneSelectionModel.selectedIndexProperty().addListener(selectedTabIndexListener);

        currencyComboBox.setItems(model.getCurrencyListItems());
        currencyComboBox.setVisibleRowCount(25);

        if (model.showAllTradeCurrenciesProperty.get())
            currencyComboBox.getSelectionModel().select(0);
        else if (model.getSelectedCurrencyListItem().isPresent())
            currencyComboBox.getSelectionModel().select(model.getSelectedCurrencyListItem().get());

        currencyComboBox.setOnAction(e -> {
            CurrencyListItem selectedItem = currencyComboBox.getSelectionModel().getSelectedItem();
            if (selectedItem != null)
                model.onSetTradeCurrency(selectedItem.tradeCurrency);
        });


        toggleGroup.getToggles().get(model.tickUnit.ordinal()).setSelected(true);

        model.priceItems.addListener(itemsChangeListener);
        toggleGroup.selectedToggleProperty().addListener(timeUnitChangeListener);
        priceAxisY.widthProperty().addListener(priceAxisYWidthListener);
        volumeAxisY.widthProperty().addListener(volumeAxisYWidthListener);
        model.tradeStatisticsByCurrency.addListener(tradeStatisticsByCurrencyListener);

        priceAxisY.labelProperty().bind(priceColumnLabel);
        priceColumn.textProperty().bind(priceColumnLabel);

        currencySelectionBinding = EasyBind.combine(
                model.showAllTradeCurrenciesProperty, model.selectedTradeCurrencyProperty,
                (showAll, selectedTradeCurrency) -> {
                    priceChart.setVisible(!showAll);
                    priceChart.setManaged(!showAll);
                    priceColumn.setSortable(!showAll);

                    if (showAll) {
                        volumeColumn.setText("Amount");
                        priceColumnLabel.set("Price");
                        if (!tableView.getColumns().contains(marketColumn))
                            tableView.getColumns().add(1, marketColumn);
                    } else {
                        priceSeries.setName(selectedTradeCurrency.getName());

                        String code = selectedTradeCurrency.getCode();
                        volumeColumn.setText("Amount in " + code);
                        priceColumnLabel.set(formatter.getPriceWithCurrencyCode(code));

                        if (tableView.getColumns().contains(marketColumn))
                            tableView.getColumns().remove(marketColumn);
                    }
                    return null;
                });

        currencySelectionSubscriber = currencySelectionBinding.subscribe((observable, oldValue, newValue) -> {
        });

        sortedList = new SortedList<>(model.tradeStatisticsByCurrency);
        sortedList.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedList);

        priceChart.setAnimated(model.preferences.getUseAnimations());
        volumeChart.setAnimated(model.preferences.getUseAnimations());
        priceAxisX.setTickLabelFormatter(getTimeAxisStringConverter());
        volumeAxisX.setTickLabelFormatter(getTimeAxisStringConverter());

        nrOfTradeStatisticsLabel.setText("Trades: " + model.tradeStatisticsByCurrency.size());

        UserThread.runAfter(this::updateChartData, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void deactivate() {
        currencyComboBox.setOnAction(null);

        tabPaneSelectionModel.selectedIndexProperty().removeListener(selectedTabIndexListener);
        model.priceItems.removeListener(itemsChangeListener);
        toggleGroup.selectedToggleProperty().removeListener(timeUnitChangeListener);
        priceAxisY.widthProperty().removeListener(priceAxisYWidthListener);
        volumeAxisY.widthProperty().removeListener(volumeAxisYWidthListener);
        model.tradeStatisticsByCurrency.removeListener(tradeStatisticsByCurrencyListener);

        priceAxisY.labelProperty().unbind();
        priceColumn.textProperty().unbind();

        currencySelectionSubscriber.unsubscribe();

        sortedList.comparatorProperty().unbind();

        priceSeries.getData().clear();
        priceChart.getData().clear();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Chart
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createCharts() {
        priceSeries = new XYChart.Series<>();

        priceAxisX = new NumberAxis(0, model.maxTicks + 1, 1);
        priceAxisX.setTickUnit(1);
        priceAxisX.setMinorTickCount(0);
        priceAxisX.setForceZeroInRange(false);
        priceAxisX.setTickLabelFormatter(getTimeAxisStringConverter());

        priceAxisY = new NumberAxis();
        priceAxisY.setForceZeroInRange(false);
        priceAxisY.setAutoRanging(true);
        priceAxisY.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number object) {
                if (CurrencyUtil.isCryptoCurrency(model.getCurrencyCode())) {
                    final double value = MathUtils.scaleDownByPowerOf10((double) object, 8);
                    return formatter.formatRoundedDoubleWithPrecision(value, 8);
                } else
                    return formatter.formatPrice(Fiat.valueOf(model.getCurrencyCode(), MathUtils.doubleToLong((double) object)));
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });

        priceChart = new CandleStickChart(priceAxisX, priceAxisY, new StringConverter<Number>() {
            @Override
            public String toString(Number object) {
                if (CurrencyUtil.isCryptoCurrency(model.getCurrencyCode())) {
                    final double value = MathUtils.scaleDownByPowerOf10((long) object, 8);
                    return formatter.formatRoundedDoubleWithPrecision(value, 8);
                } else {
                    return formatter.formatPrice(Fiat.valueOf(model.getCurrencyCode(), (long) object));
                }
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });
        priceChart.setMinHeight(198);
        priceChart.setPrefHeight(198);
        priceChart.setMaxHeight(300);
        priceChart.setLegendVisible(false);
        priceChart.setData(FXCollections.observableArrayList(priceSeries));


        volumeSeries = new XYChart.Series<>();

        volumeAxisX = new NumberAxis(0, model.maxTicks + 1, 1);
        volumeAxisX.setTickUnit(1);
        volumeAxisX.setMinorTickCount(0);
        volumeAxisX.setForceZeroInRange(false);
        volumeAxisX.setTickLabelFormatter(getTimeAxisStringConverter());

        volumeAxisY = new NumberAxis();
        volumeAxisY.setForceZeroInRange(true);
        volumeAxisY.setAutoRanging(true);
        volumeAxisY.setLabel("Volume in BTC");
        volumeAxisY.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number object) {
                return formatter.formatCoin(Coin.valueOf(MathUtils.doubleToLong((double) object)));
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });

        volumeChart = new VolumeChart(volumeAxisX, volumeAxisY, new StringConverter<Number>() {
            @Override
            public String toString(Number object) {
                return formatter.formatCoinWithCode(Coin.valueOf((long) object));
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });
        volumeChart.setData(FXCollections.observableArrayList(volumeSeries));
        volumeChart.setMinHeight(148);
        volumeChart.setPrefHeight(148);
        volumeChart.setMaxHeight(200);
        volumeChart.setLegendVisible(false);
    }

    private void updateChartData() {
        volumeSeries.getData().setAll(model.volumeItems);

        // At price chart we need to set the priceSeries new otherwise the lines are not rendered correctly 
        // TODO should be fixed in candle chart
        priceSeries.getData().clear();
        priceSeries = new XYChart.Series<>();
        priceSeries.getData().setAll(model.priceItems);
        priceChart.getData().clear();
        priceChart.setData(FXCollections.observableArrayList(priceSeries));
    }

    private void layoutChart() {
        UserThread.execute(() -> {
            if (volumeAxisYWidth > priceAxisYWidth) {
                priceChart.setPadding(new Insets(0, 0, 0, volumeAxisYWidth - priceAxisYWidth));
                volumeChart.setPadding(new Insets(0, 0, 0, 0));
            } else if (volumeAxisYWidth < priceAxisYWidth) {
                priceChart.setPadding(new Insets(0, 0, 0, 0));
                volumeChart.setPadding(new Insets(0, 0, 0, priceAxisYWidth - volumeAxisYWidth));
            }
        });
    }

    @NotNull
    private StringConverter<Number> getTimeAxisStringConverter() {
        return new StringConverter<Number>() {
            @Override
            public String toString(Number object) {
                long index = MathUtils.doubleToLong((double) object);
                long time = model.getTimeFromTickIndex(index);
                if (model.tickUnit.ordinal() <= TradesChartsViewModel.TickUnit.DAY.ordinal())
                    return index % 4 == 0 ? formatter.formatDate(new Date(time)) : "";
                else
                    return index % 3 == 0 ? formatter.formatTime(new Date(time)) : "";
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        };
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // CurrencyComboBox
    ///////////////////////////////////////////////////////////////////////////////////////////

    private HBox getToolBox() {
        Label currencyLabel = new Label("Currency:");
        currencyLabel.setPadding(new Insets(0, 4, 0, 0));

        currencyComboBox = new ComboBox<>();
        currencyComboBox.setPromptText("Select currency");
        currencyComboBox.setConverter(GUIUtil.getCurrencyListItemConverter("trades", model.preferences));

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label label = new Label("Interval:");
        label.setPadding(new Insets(0, 4, 0, 0));

        toggleGroup = new ToggleGroup();
        ToggleButton year = getToggleButton("Year", TradesChartsViewModel.TickUnit.YEAR, toggleGroup, "toggle-left");
        ToggleButton month = getToggleButton("Month", TradesChartsViewModel.TickUnit.MONTH, toggleGroup, "toggle-left");
        ToggleButton week = getToggleButton("Week", TradesChartsViewModel.TickUnit.WEEK, toggleGroup, "toggle-center");
        ToggleButton day = getToggleButton("Day", TradesChartsViewModel.TickUnit.DAY, toggleGroup, "toggle-center");
        ToggleButton hour = getToggleButton("Hour", TradesChartsViewModel.TickUnit.HOUR, toggleGroup, "toggle-center");
        ToggleButton minute10 = getToggleButton("10 Minutes", TradesChartsViewModel.TickUnit.MINUTE_10, toggleGroup, "toggle-center");

        HBox hBox = new HBox();
        hBox.setSpacing(0);
        hBox.setPadding(new Insets(5, 9, -10, 10));
        hBox.setAlignment(Pos.CENTER_LEFT);
        hBox.getChildren().addAll(currencyLabel, currencyComboBox, spacer, label, year, month, week, day, hour, minute10);
        return hBox;
    }

    private ToggleButton getToggleButton(String label, TradesChartsViewModel.TickUnit tickUnit, ToggleGroup toggleGroup, String style) {
        ToggleButton toggleButton = new ToggleButton(label);
        toggleButton.setPadding(new Insets(0, 5, 0, 5));
        toggleButton.setUserData(tickUnit);
        toggleButton.setToggleGroup(toggleGroup);
        toggleButton.setId(style);
        return toggleButton;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Table
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createTable() {
        tableView = new TableView<>();
        tableView.setMinHeight(140);
        tableView.setPrefHeight(140);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // date
        TableColumn<TradeStatistics, TradeStatistics> dateColumn = new TableColumn<TradeStatistics, TradeStatistics>("Date/Time") {
            {
                setMinWidth(190);
                setMaxWidth(190);
            }
        };
        dateColumn.setCellValueFactory((tradeStatistics) -> new ReadOnlyObjectWrapper<>(tradeStatistics.getValue()));
        dateColumn.setCellFactory(
                new Callback<TableColumn<TradeStatistics, TradeStatistics>, TableCell<TradeStatistics,
                        TradeStatistics>>() {
                    @Override
                    public TableCell<TradeStatistics, TradeStatistics> call(
                            TableColumn<TradeStatistics, TradeStatistics> column) {
                        return new TableCell<TradeStatistics, TradeStatistics>() {
                            @Override
                            public void updateItem(final TradeStatistics item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(formatter.formatDateTime(item.getTradeDate()));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        dateColumn.setComparator((o1, o2) -> o1.getTradeDate().compareTo(o2.getTradeDate()));
        tableView.getColumns().add(dateColumn);

        // market
        marketColumn = new TableColumn<TradeStatistics, TradeStatistics>("Market") {
            {
                setMinWidth(130);
                setMaxWidth(130);
            }
        };
        marketColumn.setCellValueFactory((tradeStatistics) -> new ReadOnlyObjectWrapper<>(tradeStatistics.getValue()));
        marketColumn.setCellFactory(
                new Callback<TableColumn<TradeStatistics, TradeStatistics>, TableCell<TradeStatistics,
                        TradeStatistics>>() {
                    @Override
                    public TableCell<TradeStatistics, TradeStatistics> call(
                            TableColumn<TradeStatistics, TradeStatistics> column) {
                        return new TableCell<TradeStatistics, TradeStatistics>() {
                            @Override
                            public void updateItem(final TradeStatistics item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(formatter.getCurrencyPair(item.currency));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        marketColumn.setComparator((o1, o2) -> o1.getTradeDate().compareTo(o2.getTradeDate()));
        tableView.getColumns().add(marketColumn);

        // price
        priceColumn = new TableColumn<>();
        priceColumn.setCellValueFactory((tradeStatistics) -> new ReadOnlyObjectWrapper<>(tradeStatistics.getValue()));
        priceColumn.setCellFactory(
                new Callback<TableColumn<TradeStatistics, TradeStatistics>, TableCell<TradeStatistics,
                        TradeStatistics>>() {
                    @Override
                    public TableCell<TradeStatistics, TradeStatistics> call(
                            TableColumn<TradeStatistics, TradeStatistics> column) {
                        return new TableCell<TradeStatistics, TradeStatistics>() {
                            @Override
                            public void updateItem(final TradeStatistics item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(formatter.formatPrice(item.getTradePrice()));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        priceColumn.setComparator((o1, o2) -> o1.getTradePrice().compareTo(o2.getTradePrice()));
        tableView.getColumns().add(priceColumn);

        // amount
        TableColumn<TradeStatistics, TradeStatistics> amountColumn = new TableColumn<>("Amount in BTC");
        amountColumn.setCellValueFactory((tradeStatistics) -> new ReadOnlyObjectWrapper<>(tradeStatistics.getValue()));
        amountColumn.setCellFactory(
                new Callback<TableColumn<TradeStatistics, TradeStatistics>, TableCell<TradeStatistics,
                        TradeStatistics>>() {
                    @Override
                    public TableCell<TradeStatistics, TradeStatistics> call(
                            TableColumn<TradeStatistics, TradeStatistics> column) {
                        return new TableCell<TradeStatistics, TradeStatistics>() {
                            @Override
                            public void updateItem(final TradeStatistics item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(formatter.formatCoinWithCode(item.getTradeAmount()));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        amountColumn.setComparator((o1, o2) -> o1.getTradeAmount().compareTo(o2.getTradeAmount()));
        tableView.getColumns().add(amountColumn);

        // volume
        volumeColumn = new TableColumn<>();
        volumeColumn.setCellValueFactory((tradeStatistics) -> new ReadOnlyObjectWrapper<>(tradeStatistics.getValue()));
        volumeColumn.setCellFactory(
                new Callback<TableColumn<TradeStatistics, TradeStatistics>, TableCell<TradeStatistics,
                        TradeStatistics>>() {
                    @Override
                    public TableCell<TradeStatistics, TradeStatistics> call(
                            TableColumn<TradeStatistics, TradeStatistics> column) {
                        return new TableCell<TradeStatistics, TradeStatistics>() {
                            @Override
                            public void updateItem(final TradeStatistics item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(model.showAllTradeCurrenciesProperty.get() ?
                                            formatter.formatVolumeWithCode(item.getTradeVolume()) :
                                            formatter.formatVolume(item.getTradeVolume()));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        volumeColumn.setComparator((o1, o2) -> {
            final Fiat tradeVolume1 = o1.getTradeVolume();
            final Fiat tradeVolume2 = o2.getTradeVolume();
            return tradeVolume1 != null && tradeVolume2 != null ? tradeVolume1.compareTo(tradeVolume2) : 0;
        });
        tableView.getColumns().add(volumeColumn);

        // paymentMethod
        TableColumn<TradeStatistics, TradeStatistics> paymentMethodColumn = new TableColumn<>("Payment method");
        paymentMethodColumn.setCellValueFactory((tradeStatistics) -> new ReadOnlyObjectWrapper<>(tradeStatistics.getValue()));
        paymentMethodColumn.setCellFactory(
                new Callback<TableColumn<TradeStatistics, TradeStatistics>, TableCell<TradeStatistics,
                        TradeStatistics>>() {
                    @Override
                    public TableCell<TradeStatistics, TradeStatistics> call(
                            TableColumn<TradeStatistics, TradeStatistics> column) {
                        return new TableCell<TradeStatistics, TradeStatistics>() {
                            @Override
                            public void updateItem(final TradeStatistics item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(getPaymentMethodLabel(item));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        paymentMethodColumn.setComparator((o1, o2) -> getPaymentMethodLabel(o1).compareTo(getPaymentMethodLabel(o2)));
        tableView.getColumns().add(paymentMethodColumn);

        // direction
        TableColumn<TradeStatistics, TradeStatistics> directionColumn = new TableColumn<>("Trade type");
        directionColumn.setCellValueFactory((tradeStatistics) -> new ReadOnlyObjectWrapper<>(tradeStatistics.getValue()));
        directionColumn.setCellFactory(
                new Callback<TableColumn<TradeStatistics, TradeStatistics>, TableCell<TradeStatistics,
                        TradeStatistics>>() {
                    @Override
                    public TableCell<TradeStatistics, TradeStatistics> call(
                            TableColumn<TradeStatistics, TradeStatistics> column) {
                        return new TableCell<TradeStatistics, TradeStatistics>() {
                            @Override
                            public void updateItem(final TradeStatistics item, boolean empty) {
                                super.updateItem(item, empty);
                                if (item != null)
                                    setText(getDirectionLabel(item));
                                else
                                    setText("");
                            }
                        };
                    }
                });
        directionColumn.setComparator((o1, o2) -> getDirectionLabel(o1).compareTo(getDirectionLabel(o2)));
        tableView.getColumns().add(directionColumn);

        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label placeholder = new Label("There is no data available");
        placeholder.setWrapText(true);
        tableView.setPlaceholder(placeholder);
        dateColumn.setSortType(TableColumn.SortType.DESCENDING);
        tableView.getSortOrder().add(dateColumn);
    }

    @NotNull
    private String getDirectionLabel(TradeStatistics item) {
        return formatter.getDirectionWithCode(item.direction, item.currency);
    }

    @NotNull
    private String getPaymentMethodLabel(TradeStatistics item) {
        return BSResources.get(item.paymentMethod);
    }
}
